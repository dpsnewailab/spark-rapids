/*
 * Copyright (c) 2020, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids

import com.nvidia.spark.rapids.RapidsPluginImplicits.AutoCloseableArray

import org.apache.spark.sql.rapids.TempSpillBufferId
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * Holds a ColumnarBatch that the backing buffers on it can be spilled.
 */
trait SpillableColumnarBatch extends AutoCloseable {
  /**
   * The number of rows stored in this batch.
   */
  def numRows(): Int

  /**
   * Set a new spill priority.
   */
  def setSpillPriority(priority: Long): Unit

  /**
   * Get the columnar batch.
   * @note It is the responsibility of the caller to close the batch.
   * @note If the buffer is compressed data then the resulting batch will be built using
   *       `GpuCompressedColumnVector`, and it is the responsibility of the caller to deal
   *       with decompressing the data if necessary.
   */
  def getColumnarBatch(): ColumnarBatch
}

/**
 * Cudf does not support a table with columns and no rows. This takes care of making one of those
 * spillable, even though in reality there is no backing buffer.  It does this by just keeping the
 * row count in memory, and not dealing with the catalog at all.
 */
class JustRowsColumnarBatch(numRows: Int) extends SpillableColumnarBatch {
  override def numRows(): Int = numRows
  override def setSpillPriority(priority: Long): Unit = () // NOOP nothing to spill
  override def getColumnarBatch(): ColumnarBatch =
    new ColumnarBatch(Array.empty, numRows)
  override def close(): Unit = () // NOOP nothing to close
}

/**
 * The implementation of [[SpillableColumnarBatch]] that points to buffers that can be spilled.
 * @note the buffer should be in the cache by the time this is created and this is taking over
 *       ownership of the life cycle of the batch.  So don't call this constructor directly please
 *       use `SpillableColumnarBatch.apply` instead.
 */
class SpillableColumnarBatchImpl (id: TempSpillBufferId,
    rowCount: Int,
    sparkTypes: Array[DataType])
    extends  SpillableColumnarBatch with Arm {
  private var closed = false

  /**
   * The number of rows stored in this batch.
   */
  override def numRows(): Int = rowCount

  /**
   * The ID that this is stored under.
   * @note Use with caution because if this has been closed the id is no longer valid.
   */
  def spillId: TempSpillBufferId = id

  /**
   * Set a new spill priority.
   */
  override def setSpillPriority(priority: Long): Unit = {
    withResource(RapidsBufferCatalog.acquireBuffer(id)) { rapidsBuffer =>
      rapidsBuffer.setSpillPriority(priority)
    }
  }

  /**
   * Get the columnar batch.
   * @note It is the responsibility of the caller to close the batch.
   * @note If the buffer is compressed data then the resulting batch will be built using
   *       `GpuCompressedColumnVector`, and it is the responsibility of the caller to deal
   *       with decompressing the data if necessary.
   */
  override def getColumnarBatch(): ColumnarBatch = {
    withResource(RapidsBufferCatalog.acquireBuffer(id)) { rapidsBuffer =>
      rapidsBuffer.getColumnarBatch(sparkTypes)
    }
  }

  /**
   * Remove the `ColumnarBatch` from the cache.
   */
  override def close(): Unit = {
    if (!closed) {
      RapidsBufferCatalog.removeBuffer(id)
      closed = true
    }
  }
}

object SpillableColumnarBatch extends Arm {
  /**
   * Create a new SpillableColumnarBatch.
   * @note This takes over ownership of batch, and batch should not be used after this.
   */
  def apply(batch: ColumnarBatch, priority: Long): SpillableColumnarBatch = {
    val numRows = batch.numRows()
    if (batch.numCols() <= 0) {
      // We consumed it
      batch.close()
      new JustRowsColumnarBatch(numRows)
    } else {
      val types =  GpuColumnVector.extractTypes(batch)
      val id = TempSpillBufferId()
      addBatch(id, batch, priority)
      new SpillableColumnarBatchImpl(id, numRows, types)
    }
  }

  private[this] def addBatch(
      id: RapidsBufferId,
      batch: ColumnarBatch,
      initialSpillPriority: Long): Unit = {
    val numColumns = batch.numCols()
    if (GpuCompressedColumnVector.isBatchCompressed(batch)) {
      withResource(batch) { batch =>
        val cv = batch.column(0).asInstanceOf[GpuCompressedColumnVector]
        val buff = cv.getBuffer
        buff.incRefCount()
        RapidsBufferCatalog.addBuffer(id, buff, cv.getTableMeta, initialSpillPriority)
      }
    } else if (numColumns > 0 &&
        (0 until numColumns)
            .forall(i => batch.column(i).isInstanceOf[GpuColumnVectorFromBuffer])) {
      val cv = batch.column(0).asInstanceOf[GpuColumnVectorFromBuffer]
      withResource(batch) { batch =>
        val table = GpuColumnVector.from(batch)
        val buff = cv.getBuffer
        buff.incRefCount()
        RapidsBufferCatalog.addTable(id, table, buff, initialSpillPriority)
      }
    } else {
      withResource(batch) { batch =>
        withResource(GpuColumnVector.from(batch)) { tmpTable =>
          val contigTables = tmpTable.contiguousSplit(batch.numRows())
          val tab = contigTables.head
          contigTables.tail.safeClose()
          RapidsBufferCatalog.addTable(id, tab.getTable, tab.getBuffer, initialSpillPriority)
        }
      }
    }
  }
}