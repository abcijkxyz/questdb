/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.MessageBus;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.AbstractQueueConsumerJob;
import io.questdb.mp.RingQueue;
import io.questdb.mp.SOUnboundedCountDownLatch;
import io.questdb.mp.Sequence;
import io.questdb.std.*;
import io.questdb.std.str.Path;
import io.questdb.tasks.OutOfOrderCopyTask;
import io.questdb.tasks.OutOfOrderOpenColumnTask;
import io.questdb.tasks.OutOfOrderPartitionTask;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.questdb.cairo.TableUtils.dFile;
import static io.questdb.cairo.TableUtils.readPartitionSize;
import static io.questdb.cairo.TableWriter.*;

public class OutOfOrderPartitionJob extends AbstractQueueConsumerJob<OutOfOrderPartitionTask> {

    private static final Log LOG = LogFactory.getLog(OutOfOrderPartitionJob.class);
    private final CairoConfiguration configuration;
    private final RingQueue<OutOfOrderOpenColumnTask> openColumnTaskOutboundQueue;
    private final Sequence openColumnPubSeq;
    private final RingQueue<OutOfOrderCopyTask> copyTaskOutboundQueue;
    private final Sequence copyTaskPubSeq;

    public OutOfOrderPartitionJob(MessageBus messageBus) {
        super(messageBus.getOutOfOrderPartitionQueue(), messageBus.getOutOfOrderPartitionSubSeq());
        this.configuration = messageBus.getConfiguration();
        this.openColumnTaskOutboundQueue = messageBus.getOutOfOrderOpenColumnQueue();
        this.openColumnPubSeq = messageBus.getOutOfOrderOpenColumnPubSequence();
        this.copyTaskOutboundQueue = messageBus.getOutOfOrderCopyQueue();
        this.copyTaskPubSeq = messageBus.getOutOfOrderCopyPubSequence();
    }

    public static final AtomicLong call_count = new AtomicLong();
    public static void processPartition(
            CairoConfiguration configuration,
            RingQueue<OutOfOrderOpenColumnTask> openColumnTaskOutboundQueue,
            Sequence openColumnPubSeq,
            RingQueue<OutOfOrderCopyTask> copyTaskOutboundQueue,
            Sequence copyTaskPubSeq,
            FilesFacade ff,
            CharSequence pathToTable,
            TableWriterMetadata metadata,
            ObjList<AppendMemory> columns,
            ObjList<ContiguousVirtualMemory> oooColumns,
            long srcOooLo,
            long srcOooHi,
            long txn,
            long sortedTimestampsAddr,
            long lasPartitionSize,
            long partitionTimestampHi,
            long oooTimestampMax,
            long tableCeilOfMaxTimestamp,
            long tableFloorOfMinTimestamp,
            long tableFloorOfMaxTimestamp,
            long tableMaxTimestamp,
            int partitionBy,
            int timestampIndex,
            SOUnboundedCountDownLatch doneLatch
    ) {
        call_count.incrementAndGet();
        final Path path = Path.getThreadLocal(pathToTable);
        final long oooPartitionTimestampMin = getTimestampIndexValue(sortedTimestampsAddr, srcOooLo);
        TableUtils.setPathForPartition(path, partitionBy, oooPartitionTimestampMin);
        // plen has to include partition directory name

        final int plen = path.length();
        long timestampFd;
        long dataTimestampLo;
        long dataTimestampHi;
        long srcDataMax;

        // is out of order data hitting the last partition?
        // if so we do not need to re-open files and and write to existing file descriptors

        if (partitionTimestampHi > tableCeilOfMaxTimestamp || partitionTimestampHi < tableFloorOfMinTimestamp) {

            // this has to be a brand new partition for either of two cases:
            // - this partition is above min partition of the table
            // - this partition is below max partition of the table
            // pure OOO data copy into new partition

            // todo: handle errors
            LOG.debug().$("would create [path=").$(path.chopZ().put(Files.SEPARATOR).$()).$(']').$();
            OutOfOrderUtils.createDirsOrFail(ff, path, configuration.getMkDirMode());

            publishOpenColumnTasks(
                    configuration,
                    openColumnTaskOutboundQueue,
                    openColumnPubSeq,
                    copyTaskOutboundQueue,
                    copyTaskPubSeq,
                    ff,
                    txn,
                    metadata,
                    columns,
                    oooColumns,
                    pathToTable,
                    oooPartitionTimestampMin,
                    partitionBy,
                    srcOooLo,
                    srcOooHi,
                    // below parameters are unused by this type of append
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    5, //oooOpenNewPartitionForAppend
                    -1,  // timestamp fd
                    timestampIndex,
                    sortedTimestampsAddr,
                    doneLatch
            );
        } else {

            // out of order is hitting existing partition
            final long timestampColumnAddr;
            final long timestampColumnSize;
            if (partitionTimestampHi == tableCeilOfMaxTimestamp) {
                dataTimestampHi = tableMaxTimestamp;
                srcDataMax = lasPartitionSize;
                timestampColumnSize = srcDataMax * 8L;
                // negative fd indicates descriptor reuse
                timestampFd = -columns.getQuick(getPrimaryColumnIndex(timestampIndex)).getFd();
                timestampColumnAddr = oooMapTimestampRO(ff, -timestampFd, timestampColumnSize);
            } else {
                long tempMem8b = Unsafe.malloc(Long.BYTES);
                try {
                    srcDataMax = readPartitionSize(ff, path, tempMem8b);
                } finally {
                    Unsafe.free(tempMem8b, Long.BYTES);
                }
                timestampColumnSize = srcDataMax * 8L;
                // out of order data is going into archive partition
                // we need to read "low" and "high" boundaries of the partition. "low" being oldest timestamp
                // and "high" being newest

                dFile(path.trimTo(plen), metadata.getColumnName(timestampIndex));

                // also track the fd that we need to eventually close
                timestampFd = ff.openRW(path);
                if (timestampFd == -1) {
                    throw CairoException.instance(ff.errno()).put("could not open `").put(pathToTable).put('`');
                }
                timestampColumnAddr = oooMapTimestampRO(ff, timestampFd, timestampColumnSize);
                dataTimestampHi = Unsafe.getUnsafe().getLong(timestampColumnAddr + timestampColumnSize - Long.BYTES);
            }
            dataTimestampLo = Unsafe.getUnsafe().getLong(timestampColumnAddr);


            // create copy jobs
            // we will have maximum of 3 stages:
            // - prefix data
            // - merge job
            // - suffix data
            //
            // prefix and suffix can be sourced either from OO fully or from Data (written to disk) fully
            // so for prefix and suffix we will need a flag indicating source of the data
            // as well as range of rows in that source

            int prefixType = OO_BLOCK_NONE;
            long prefixLo = -1;
            long prefixHi = -1;
            int mergeType = OO_BLOCK_NONE;
            long mergeDataLo = -1;
            long mergeDataHi = -1;
            long mergeOOOLo = -1;
            long mergeOOOHi = -1;
            int suffixType = OO_BLOCK_NONE;
            long suffixLo = -1;
            long suffixHi = -1;

            try {
                if (oooPartitionTimestampMin < dataTimestampLo) {

                    prefixType = OO_BLOCK_OO;
                    prefixLo = srcOooLo;
                    if (dataTimestampLo < oooTimestampMax) {

                        //            +-----+
                        //            | OOO |
                        //
                        //  +------+
                        //  | data |

                        mergeDataLo = 0;
                        prefixHi = Vect.binarySearchIndexT(sortedTimestampsAddr, dataTimestampLo, srcOooLo, srcOooHi, BinarySearch.SCAN_DOWN);
                        mergeOOOLo = prefixHi + 1;
                    } else {
                        //            +-----+
                        //            | OOO |
                        //            +-----+
                        //
                        //  +------+
                        //  | data |
                        //
                        prefixHi = srcOooHi;
                    }

                    if (oooTimestampMax >= dataTimestampLo) {

                        //
                        //  +------+  | OOO |
                        //  | data |  +-----+
                        //  |      |

                        if (oooTimestampMax < dataTimestampHi) {

                            // |      | |     |
                            // |      | | OOO |
                            // | data | +-----+
                            // |      |
                            // +------+

                            mergeType = OO_BLOCK_MERGE;
                            mergeOOOHi = srcOooHi;
                            mergeDataHi = Vect.binarySearch64Bit(timestampColumnAddr, oooTimestampMax, 0, srcDataMax - 1, BinarySearch.SCAN_DOWN);

                            suffixLo = mergeDataHi + 1;
                            suffixType = OO_BLOCK_DATA;
                            suffixHi = srcDataMax - 1;

                        } else if (oooTimestampMax > dataTimestampHi) {

                            // |      | |     |
                            // |      | | OOO |
                            // | data | |     |
                            // +------+ |     |
                            //          +-----+

                            mergeDataHi = srcDataMax - 1;
                            mergeOOOHi = Vect.binarySearchIndexT(sortedTimestampsAddr, dataTimestampHi - 1, mergeOOOLo, srcOooHi, BinarySearch.SCAN_DOWN) + 1;

                            if (mergeOOOLo < mergeOOOHi) {
                                mergeType = OO_BLOCK_MERGE;
                            } else {
                                mergeType = OO_BLOCK_DATA;
                                mergeOOOHi--;
                            }

                            if (mergeOOOHi < srcOooHi) {
                                suffixLo = mergeOOOHi + 1;
                                suffixType = OO_BLOCK_OO;
                                suffixHi = Math.max(suffixLo, srcOooHi);
                            } else {
                                suffixType = OO_BLOCK_NONE;
                            }
                        } else {

                            // |      | |     |
                            // |      | | OOO |
                            // | data | |     |
                            // +------+ +-----+

                            mergeType = OO_BLOCK_MERGE;
                            mergeOOOHi = srcOooHi;
                            mergeDataHi = srcDataMax - 1;
                        }
                    } else {

                        //            +-----+
                        //            | OOO |
                        //            +-----+
                        //
                        //  +------+
                        //  | data |

                        suffixType = OO_BLOCK_DATA;
                        suffixLo = 0;
                        suffixHi = srcDataMax - 1;
                    }
                } else {
                    if (oooPartitionTimestampMin <= dataTimestampLo) {
                        //            +-----+
                        //            | OOO |
                        //            +-----+
                        //
                        //  +------+
                        //  | data |
                        //

                        prefixType = OO_BLOCK_OO;
                        prefixLo = srcOooLo;
                        prefixHi = srcOooHi;

                        suffixType = OO_BLOCK_DATA;
                        suffixLo = 0;
                        suffixHi = srcDataMax - 1;
                    } else {
                        //   +------+                +------+  +-----+
                        //   | data |  +-----+       | data |  |     |
                        //   |      |  | OOO |  OR   |      |  | OOO |
                        //   |      |  |     |       |      |  |     |

                        if (oooPartitionTimestampMin <= dataTimestampHi) {

                            //
                            // +------+
                            // |      |
                            // |      | +-----+
                            // | data | | OOO |
                            // +------+

                            prefixType = OO_BLOCK_DATA;
                            prefixLo = 0;
                            prefixHi = Vect.binarySearch64Bit(timestampColumnAddr, oooPartitionTimestampMin, 0, srcDataMax - 1, BinarySearch.SCAN_DOWN);
                            mergeDataLo = prefixHi + 1;
                            mergeOOOLo = srcOooLo;

                            if (oooTimestampMax < dataTimestampHi) {

                                //
                                // |      | +-----+
                                // | data | | OOO |
                                // |      | +-----+
                                // +------+

                                mergeOOOHi = srcOooHi;
                                mergeDataHi = Vect.binarySearch64Bit(timestampColumnAddr, oooTimestampMax - 1, mergeDataLo, srcDataMax - 1, BinarySearch.SCAN_DOWN) + 1;

                                if (mergeDataLo < mergeDataHi) {
                                    mergeType = OO_BLOCK_MERGE;
                                } else {
                                    // the OO data implodes right between rows of existing data
                                    // so we will have both data prefix and suffix and the middle bit

                                    // is the out of order
                                    mergeType = OO_BLOCK_OO;
                                    mergeDataHi--;
                                }

                                suffixType = OO_BLOCK_DATA;
                                suffixLo = mergeDataHi + 1;
                                suffixHi = srcDataMax - 1;
                            } else if (oooTimestampMax > dataTimestampHi) {

                                //
                                // |      | +-----+
                                // | data | | OOO |
                                // |      | |     |
                                // +------+ |     |
                                //          |     |
                                //          +-----+

                                mergeOOOHi = Vect.binarySearchIndexT(sortedTimestampsAddr, dataTimestampHi, srcOooLo, srcOooHi, BinarySearch.SCAN_UP);
                                mergeDataHi = srcDataMax - 1;

                                mergeType = OO_BLOCK_MERGE;
                                suffixType = OO_BLOCK_OO;
                                suffixLo = mergeOOOHi + 1;
                                suffixHi = srcOooHi;
                            } else {

                                //
                                // |      | +-----+
                                // | data | | OOO |
                                // |      | |     |
                                // +------+ +-----+
                                //

                                mergeType = OO_BLOCK_MERGE;
                                mergeOOOHi = srcOooHi;
                                mergeDataHi = srcDataMax - 1;
                            }
                        } else {

                            // +------+
                            // | data |
                            // |      |
                            // +------+
                            //
                            //           +-----+
                            //           | OOO |
                            //           |     |
                            //
                            suffixType = OO_BLOCK_OO;
                            suffixLo = srcOooLo;
                            suffixHi = srcOooHi;
                        }
                    }
                }
            } finally {
                ff.munmap(timestampColumnAddr, timestampColumnSize);
            }

            path.trimTo(plen);
            final int openColumnMode;
            if (prefixType == OO_BLOCK_NONE && mergeType == OO_BLOCK_NONE) {
                // We do not need to create a copy of partition when we simply need to append
                // existing the one.
                if (partitionTimestampHi < tableFloorOfMaxTimestamp) {
                    openColumnMode = 1; // oooOpenMidPartitionForAppend
                } else {
                    openColumnMode = 2; // oooOpenLastPartitionForAppend
                }
            } else {
                OutOfOrderUtils.appendTxnToPath(path.trimTo(plen), txn);
                // todo: handle errors
                OutOfOrderUtils.createDirsOrFail(ff, path.put(Files.SEPARATOR).$(), configuration.getMkDirMode());
                if (timestampFd > -1) {
                    openColumnMode = 3; // oooOpenMidPartitionForMerge
                } else {
                    openColumnMode = 4; // oooOpenLastPartitionForMerge
                }
            }

            publishOpenColumnTasks(
                    configuration,
                    openColumnTaskOutboundQueue,
                    openColumnPubSeq,
                    copyTaskOutboundQueue,
                    copyTaskPubSeq,
                    ff,
                    txn,
                    metadata,
                    columns,
                    oooColumns,
                    pathToTable,
                    oooPartitionTimestampMin,
                    partitionBy,
                    srcOooLo,
                    srcOooHi,
                    prefixType,
                    prefixLo,
                    prefixHi,
                    mergeType,
                    mergeDataLo,
                    mergeDataHi,
                    mergeOOOLo,
                    mergeOOOHi,
                    suffixType,
                    suffixLo,
                    suffixHi,
                    srcDataMax,
                    openColumnMode,
                    timestampFd,
                    timestampIndex,
                    sortedTimestampsAddr,
                    doneLatch
            );
        }
    }

    private static long oooMapTimestampRO(FilesFacade ff, long fd, long size) {
        final long address = ff.mmap(fd, size, 0, Files.MAP_RO);
        if (address == FilesFacade.MAP_FAILED) {
            throw CairoException.instance(ff.errno())
                    .put("Could not mmap ")
                    .put(" [size=").put(size)
                    .put(", fd=").put(fd)
                    .put(", memUsed=").put(Unsafe.getMemUsed())
                    .put(", fileLen=").put(ff.length(fd))
                    .put(']');
        }
        return address;
    }

    private static long oooCreateMergeIndex(
            long srcDataTimestampAddr,
            long mergedTimestamps,
            long mergeDataLo,
            long mergeDataHi,
            long mergeOOOLo,
            long mergeOOOHi
    ) {
        // Create "index" for existing timestamp column. When we reshuffle timestamps during merge we will
        // have to go back and find data rows we need to move accordingly
        final long indexSize = (mergeDataHi - mergeDataLo + 1) * TIMESTAMP_MERGE_ENTRY_BYTES;
        final long indexStruct = Unsafe.malloc(TIMESTAMP_MERGE_ENTRY_BYTES * 2);
        final long index = Unsafe.malloc(indexSize);
        Vect.makeTimestampIndex(srcDataTimestampAddr, mergeDataLo, mergeDataHi, index);
        Unsafe.getUnsafe().putLong(indexStruct, index);
        Unsafe.getUnsafe().putLong(indexStruct + Long.BYTES, mergeDataHi - mergeDataLo + 1);
        Unsafe.getUnsafe().putLong(indexStruct + 2 * Long.BYTES, mergedTimestamps + mergeOOOLo * 16);
        Unsafe.getUnsafe().putLong(indexStruct + 3 * Long.BYTES, mergeOOOHi - mergeOOOLo + 1);
        final long result = Vect.mergeLongIndexesAsc(indexStruct, 2);
        Unsafe.free(index, indexSize);
        Unsafe.free(indexStruct, TIMESTAMP_MERGE_ENTRY_BYTES * 2);
        return result;
    }

    private static void publishOpenColumnTaskUncontended(
            RingQueue<OutOfOrderOpenColumnTask> openColumnTaskOutboundQueue,
            Sequence openColumnPubSeq,
            long cursor,
            int openColumnMode,
            FilesFacade ff,
            CharSequence pathToTable,
            long partitionTimestamp,
            int partitionBy,
            CharSequence columnName,
            AtomicInteger columnCounter,
            int columnType,
            long timestampMergeIndexAddr,
            long srcOooFixAddr,
            long srcOooFixSize,
            long srcOooVarAddr,
            long srcOooVarSize,
            long srcOooLo,
            long srcOooHi,
            long srcDataMax,
            long txn,
            int prefixType,
            long prefixLo,
            long prefixHi,
            int mergeType,
            long mergeDataLo,
            long mergeDataHi,
            long mergeOOOLo,
            long mergeOOOHi,
            int suffixType,
            long suffixLo,
            long suffixHi,
            boolean isIndexed,
            long timestampFd,
            AppendMemory srcDataFixMemory,
            AppendMemory srcDataVarMemory,
            SOUnboundedCountDownLatch doneLatch
    ) {
        final OutOfOrderOpenColumnTask openColumnTask = openColumnTaskOutboundQueue.get(cursor);
        openColumnTask.of(
                openColumnMode,
                ff,
                pathToTable,
                partitionTimestamp,
                partitionBy,
                columnName,
                columnCounter,
                columnType,
                timestampMergeIndexAddr,
                srcOooFixAddr,
                srcOooFixSize,
                srcOooVarAddr,
                srcOooVarSize,
                srcOooLo,
                srcOooHi,
                srcDataMax,
                txn,
                prefixType,
                prefixLo,
                prefixHi,
                mergeType,
                mergeDataLo,
                mergeDataHi,
                mergeOOOLo,
                mergeOOOHi,
                suffixType,
                suffixLo,
                suffixHi,
                timestampFd,
                isIndexed,
                srcDataFixMemory,
                srcDataVarMemory,
                doneLatch
        );
        openColumnPubSeq.done(cursor);
    }

    private static void publishOpenColumnTasks(
            CairoConfiguration configuration,
            RingQueue<OutOfOrderOpenColumnTask> openColumnTaskOutboundQueue,
            Sequence openColumnPubSeq,
            RingQueue<OutOfOrderCopyTask> copyTaskOutboundQueue,
            Sequence copyTaskPubSeq,
            FilesFacade ff,
            long txn,
            TableWriterMetadata metadata,
            ObjList<AppendMemory> columns,
            ObjList<ContiguousVirtualMemory> oooColumns,
            CharSequence pathToTable,
            long partitionTimestamp,
            int partitionBy,
            long srcOooLo,
            long srcOooHi,
            int prefixType,
            long prefixLo,
            long prefixHi,
            int mergeType,
            long mergeDataLo,
            long mergeDataHi,
            long mergeOOOLo,
            long mergeOOOHi,
            int suffixType,
            long suffixLo,
            long suffixHi,
            long srcDataMax,
            int openColumnMode,
            long timestampFd,
            int timestampIndex,
            long sortedTimestampsAddr,
            SOUnboundedCountDownLatch doneLatch
    ) {
        LOG.debug().$("partition [ts=").$ts(partitionTimestamp).$(']').$();

        final long timestampMergeIndexAddr;
        if (mergeType == OO_BLOCK_MERGE) {
            timestampMergeIndexAddr = oooCreateMergeIndex(
                    columns.getQuick(TableWriter.getPrimaryColumnIndex(timestampIndex)).addressOf(0),
                    sortedTimestampsAddr,
                    mergeDataLo,
                    mergeDataHi,
                    mergeOOOLo,
                    mergeOOOHi
            );
        } else {
            timestampMergeIndexAddr = 0;
        }

        final int columnCount = metadata.getColumnCount();
        // todo: cache
        AtomicInteger columnCounter = new AtomicInteger(columnCount);
        for (int i = 0; i < columnCount; i++) {
            final int colOffset = TableWriter.getPrimaryColumnIndex(i);
            final boolean notTheTimestamp = i != timestampIndex;
            final int columnType = metadata.getColumnType(i);
            final ContiguousVirtualMemory oooMem1 = oooColumns.getQuick(colOffset);
            final ContiguousVirtualMemory oooMem2 = oooColumns.getQuick(colOffset + 1);
            final AppendMemory mem1 = columns.getQuick(colOffset);
            final AppendMemory mem2 = columns.getQuick(colOffset + 1);
            final AppendMemory srcDataFixMemory;
            final AppendMemory srcDataVarMemory;
            final long srcOooFixAddr;
            final long srcOooFixSize;
            final long srcOooVarAddr;
            final long srcOooVarSize;
            if (columnType != ColumnType.STRING && columnType != ColumnType.BINARY) {
                srcDataFixMemory = mem1;
                srcDataVarMemory = mem2;
                srcOooFixAddr = oooMem1.addressOf(0);
                srcOooFixSize = oooMem1.getAppendOffset();
                srcOooVarAddr = 0;
                srcOooVarSize = 0;
            } else {
                srcDataFixMemory = mem2;
                srcDataVarMemory = mem1;
                srcOooFixAddr = oooMem2.addressOf(0);
                srcOooFixSize = oooMem2.getAppendOffset();
                srcOooVarAddr = oooMem1.addressOf(0);
                srcOooVarSize = oooMem1.getAppendOffset();
            }

            final CharSequence columnName = metadata.getColumnName(i);
            final boolean isIndexed = metadata.isColumnIndexed(i);

            final long cursor = openColumnPubSeq.next();
            if (cursor > -1) {
                publishOpenColumnTaskUncontended(
                        openColumnTaskOutboundQueue,
                        openColumnPubSeq,
                        cursor,
                        openColumnMode,
                        ff,
                        pathToTable,
                        partitionTimestamp,
                        partitionBy,
                        columnName,
                        columnCounter,
                        notTheTimestamp ? columnType : -columnType,
                        timestampMergeIndexAddr,
                        srcOooFixAddr,
                        srcOooFixSize,
                        srcOooVarAddr,
                        srcOooVarSize,
                        srcOooLo,
                        srcOooHi,
                        srcDataMax,
                        txn,
                        prefixType,
                        prefixLo,
                        prefixHi,
                        mergeType,
                        mergeDataLo,
                        mergeDataHi,
                        mergeOOOLo,
                        mergeOOOHi,
                        suffixType,
                        suffixLo,
                        suffixHi,
                        isIndexed,
                        notTheTimestamp ? -1 : timestampFd,
                        srcDataFixMemory,
                        srcDataVarMemory,
                        doneLatch
                );
            } else {
                publishOpenColumnTaskContended(
                        configuration,
                        openColumnTaskOutboundQueue,
                        openColumnPubSeq,
                        copyTaskOutboundQueue,
                        copyTaskPubSeq,
                        cursor,
                        openColumnMode,
                        ff,
                        pathToTable,
                        partitionTimestamp,
                        partitionBy,
                        columnName,
                        columnCounter,
                        notTheTimestamp ? columnType : -columnType,
                        timestampMergeIndexAddr,
                        srcOooFixAddr,
                        srcOooFixSize,
                        srcOooVarAddr,
                        srcOooVarSize,
                        srcOooLo,
                        srcOooHi,
                        srcDataMax,
                        txn,
                        prefixType,
                        prefixLo,
                        prefixHi,
                        mergeType,
                        mergeDataLo,
                        mergeDataHi,
                        mergeOOOLo,
                        mergeOOOHi,
                        suffixType,
                        suffixLo,
                        suffixHi,
                        notTheTimestamp ? -1 : timestampFd,
                        isIndexed,
                        srcDataFixMemory,
                        srcDataVarMemory,
                        doneLatch
                );
            }
        }
    }

    private static void publishOpenColumnTaskContended(
            CairoConfiguration configuration,
            RingQueue<OutOfOrderOpenColumnTask> openColumnTaskOutboundQueue,
            Sequence openColumnPubSeq,
            RingQueue<OutOfOrderCopyTask> copyTaskOutboundQueue,
            Sequence copyTaskPubSeq,
            long cursor,
            int openColumnMode,
            FilesFacade ff,
            CharSequence pathToTable,
            long partitionTimestamp,
            int partitionBy,
            CharSequence columnName,
            AtomicInteger columnCounter,
            int columnType,
            long timestampMergeIndexAddr,
            long srcOooFixAddr,
            long srcOooFixSize,
            long srcOooVarAddr,
            long srcOooVarSize,
            long srcOooLo,
            long srcOooHi,
            long srcDataMax,
            long txn,
            int prefixType,
            long prefixLo,
            long prefixHi,
            int mergeType,
            long mergeDataLo,
            long mergeDataHi,
            long mergeOOOLo,
            long mergeOOOHi,
            int suffixType,
            long suffixLo,
            long suffixHi,
            long timestampFd,
            boolean isIndexed,
            AppendMemory srcDataFixMemory,
            AppendMemory srcDataVarMemory,
            SOUnboundedCountDownLatch doneLatch
    ) {
        while (cursor == -2) {
            cursor = openColumnPubSeq.next();
        }

        if (cursor > -1) {
            publishOpenColumnTaskUncontended(
                    openColumnTaskOutboundQueue,
                    openColumnPubSeq,
                    cursor,
                    openColumnMode,
                    ff,
                    pathToTable,
                    partitionTimestamp,
                    partitionBy,
                    columnName,
                    columnCounter,
                    columnType,
                    timestampMergeIndexAddr,
                    srcOooFixAddr,
                    srcOooFixSize,
                    srcOooVarAddr,
                    srcOooVarSize,
                    srcOooLo,
                    srcOooHi,
                    srcDataMax,
                    txn,
                    prefixType,
                    prefixLo,
                    prefixHi,
                    mergeType,
                    mergeDataLo,
                    mergeDataHi,
                    mergeOOOLo,
                    mergeOOOHi,
                    suffixType,
                    suffixLo,
                    suffixHi,
                    isIndexed,
                    timestampFd,
                    srcDataFixMemory,
                    srcDataVarMemory,
                    doneLatch
            );
        } else {
            OutOfOrderOpenColumnJob.openColumn(
                    configuration,
                    copyTaskOutboundQueue,
                    copyTaskPubSeq,
                    openColumnMode,
                    ff,
                    pathToTable,
                    partitionTimestamp,
                    partitionBy,
                    columnName,
                    columnCounter,
                    columnType,
                    timestampMergeIndexAddr,
                    srcOooFixAddr,
                    srcOooFixSize,
                    srcOooVarAddr,
                    srcOooVarSize,
                    srcOooLo,
                    srcOooHi,
                    srcDataMax,
                    txn,
                    prefixType,
                    prefixLo,
                    prefixHi,
                    mergeType,
                    mergeOOOLo,
                    mergeOOOHi,
                    mergeDataLo,
                    mergeDataHi,
                    mergeDataHi,
                    suffixType,
                    suffixLo,
                    suffixHi,
                    timestampFd,
                    isIndexed,
                    srcDataFixMemory,
                    srcDataVarMemory,
                    doneLatch
            );
        }
    }

    @Override
    protected boolean doRun(int workerId, long cursor) {
        OutOfOrderPartitionTask task = queue.get(cursor);
        // copy task on stack so that publisher has fighting chance of
        // publishing all it has to the queue

//        final boolean locked = task.tryLock();
//        if (locked) {
            processPartition(task, cursor, subSeq);
//        } else {
//            System.out.println("foooooooooooooooooooooooooooooooooooooooooooooooooooooook");
//            subSeq.done(cursor);
//        }

        return true;
    }

    private void processPartition(OutOfOrderPartitionTask task, long cursor, Sequence subSeq) {
        // find "current" partition boundary in the out of order data
        // once we know the boundary we can move on to calculating another one
        // srcOooHi is index inclusive of value
        final long srcOooLo = task.getSrcOooLo();
        final long srcOooHi = task.getSrcOooHi();
        final long lastPartitionSize = task.getLastPartitionSize();
        final long partitionTimestampHi = task.getPartitionTimestampHi();
        final long oooTimestampMax = task.getOooTimestampMax();
        final FilesFacade ff = task.getFf();
        final long tableCeilOfMaxTimestamp = task.getTableCeilOfMaxTimestamp();
        final long tableFloorOfMinTimestamp = task.getTableFloorOfMinTimestamp();
        final long tableFloorOfMaxTimestamp = task.getTableFloorOfMaxTimestamp();
        final long tableMaxTimestamp = task.getTableMaxTimestamp();
        final ObjList<AppendMemory> columns = task.getColumns();
        final ObjList<ContiguousVirtualMemory> oooColumns = task.getOooColumns();
        final TableWriterMetadata metadata = task.getMetadata();
        final CharSequence path = task.getPathToTable();
        final int partitionBy = task.getPartitionBy();
        final int timestampIndex = task.getTimestampIndex();
        final long sortedTimestampsAddr = task.getSortedTimestampsAddr();
        final long txn = task.getTxn();
        final SOUnboundedCountDownLatch doneLatch = task.getDoneLatch();

        subSeq.done(cursor);

        processPartition(
                configuration,
                openColumnTaskOutboundQueue,
                openColumnPubSeq,
                copyTaskOutboundQueue,
                copyTaskPubSeq,
                ff,
                path,
                metadata,
                columns,
                oooColumns,
                srcOooLo,
                srcOooHi,
                txn,
                sortedTimestampsAddr,
                lastPartitionSize,
                partitionTimestampHi,
                oooTimestampMax,
                tableCeilOfMaxTimestamp,
                tableFloorOfMinTimestamp,
                tableFloorOfMaxTimestamp,
                tableMaxTimestamp,
                partitionBy,
                timestampIndex,
                doneLatch
        );
    }
}
