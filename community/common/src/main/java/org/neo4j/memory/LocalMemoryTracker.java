/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.memory;

import static java.lang.Math.max;
import static java.util.Objects.requireNonNull;
import static org.neo4j.memory.MemoryPools.NO_TRACKING;
import static org.neo4j.util.Preconditions.checkState;
import static org.neo4j.util.Preconditions.requireNonNegative;
import static org.neo4j.util.Preconditions.requirePositive;

/**
 * Memory allocation tracker that can be used in local context that required tracking of memory that is independent from global. You can impose a limit on the
 * total number of allocated bytes.
 * <p>
 * To reduce contention on the parent tracker, locally reserved bytes are batched from the parent to a local pool. Once the pool is used up, new bytes will be
 * reserved. Calling {@link #reset()} will give back all the reserved bytes to the parent. Forgetting to call this will "leak" bytes and starve the database of
 * allocations.
 */
public class LocalMemoryTracker implements MemoryTracker
{
    private static final long NO_LIMIT = Long.MAX_VALUE;
    private static final long DEFAULT_GRAB_SIZE = 1024;

    /**
     * Imposes limits on a {@link MemoryGroup} level, e.g. global maximum transactions size
     */
    private final MemoryPool memoryPool;

    /**
     * The chunk size to reserve from the memory pool
     */
    private final long grabSize;

    /**
     * A per tracker limit.
     */
    private long localBytesLimit;

    /**
     * Number of bytes we are allowed to use on the heap. If this run out, we need to reserve more from the parent.
     */
    private long localHeapPool;

    /**
     * The current size of the tracked heap
     */
    private long allocatedBytesHeap;

    /**
     * The currently allocated off heap
     */
    private long allocatedBytesNative;

    /**
     * The heap high water mark, i.e. the maximum observed allocated heap bytes
     */
    private long heapHighWaterMark;

    public LocalMemoryTracker()
    {
        this( NO_TRACKING, NO_LIMIT, DEFAULT_GRAB_SIZE );
    }

    public LocalMemoryTracker( MemoryPool memoryPool )
    {
        this( memoryPool, NO_LIMIT, DEFAULT_GRAB_SIZE );
    }

    public LocalMemoryTracker( MemoryPool memoryPool, long localBytesLimit, long grabSize )
    {
        this.memoryPool = requireNonNull( memoryPool );
        this.localBytesLimit = localBytesLimit == 0 ? NO_LIMIT : requireNonNegative( localBytesLimit );
        this.grabSize = requireNonNegative( grabSize );
    }

    @Override
    public void allocateNative( long bytes )
    {
        if ( bytes == 0 )
        {
            return;
        }
        requirePositive( bytes );

        this.allocatedBytesNative += bytes;

        if ( allocatedBytesHeap + allocatedBytesNative > localBytesLimit )
        {
            allocatedBytesNative -= bytes;
            throw new MemoryLimitExceeded( bytes, localBytesLimit, allocatedBytesHeap + allocatedBytesNative );
        }

        this.memoryPool.reserveNative( bytes );
    }

    @Override
    public void releaseNative( long bytes )
    {
        this.allocatedBytesNative -= bytes;
        this.memoryPool.releaseNative( bytes );
    }

    @Override
    public void allocateHeap( long bytes )
    {
        if ( bytes == 0 )
        {
            return;
        }
        requirePositive( bytes );

        allocatedBytesHeap += bytes;

        if ( allocatedBytesHeap + allocatedBytesNative > localBytesLimit )
        {
            allocatedBytesHeap -= bytes;
            throw new MemoryLimitExceeded( bytes, localBytesLimit, allocatedBytesHeap + allocatedBytesNative );
        }

        if ( allocatedBytesHeap > heapHighWaterMark )
        {
            heapHighWaterMark = allocatedBytesHeap;
        }

        if ( allocatedBytesHeap > localHeapPool )
        {
            long grab = max( bytes, grabSize );
            reserveHeapFromPool( grab );
        }
    }

    @Override
    public void releaseHeap( long bytes )
    {
        requireNonNegative( bytes );
        allocatedBytesHeap -= bytes;
    }

    @Override
    public long heapHighWaterMark()
    {
        return heapHighWaterMark;
    }

    /**
     * @return number of used bytes.
     */
    @Override
    public long usedNativeMemory()
    {
        return allocatedBytesNative;
    }

    @Override
    public long estimatedHeapMemory()
    {
        return allocatedBytesHeap;
    }

    @Override
    public void reset()
    {
        checkState( allocatedBytesNative == 0, "Potential direct memory leak" );
        memoryPool.releaseHeap( localHeapPool );
        localHeapPool = 0;
        allocatedBytesHeap = 0;
        heapHighWaterMark = 0;
    }

    public void setLimit( long localBytesLimit )
    {
        this.localBytesLimit = validateLimit( localBytesLimit );
    }

    /**
     * Will reserve heap in the provided pool.
     *
     * @param size heap space to reserve for the local pool
     * @throws MemoryLimitExceeded if not enough free memory
     */
    private void reserveHeapFromPool( long size )
    {
        memoryPool.reserveHeap( size );
        localHeapPool += size;
    }

    private static long validateLimit( long localBytesLimit )
    {
        return localBytesLimit == 0 ? NO_LIMIT : requireNonNegative( localBytesLimit );
    }
}
