/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.kernel.api.impl.index;

import org.apache.lucene.index.CheckIndex;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.helpers.ArrayUtil;
import org.neo4j.helpers.Exceptions;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.io.IOUtils;
import org.neo4j.kernel.api.impl.index.partition.IndexPartition;
import org.neo4j.kernel.api.impl.index.partition.PartitionSearcher;
import org.neo4j.kernel.api.impl.index.storage.PartitionedIndexStorage;

import static java.util.stream.Collectors.toList;

// todo: this component has an implicit possibility to be opened and closed multiple times. we should revisit and at least test it.
public abstract class AbstractLuceneIndex implements Closeable
{
    protected final ReentrantLock commitCloseLock = new ReentrantLock();
    protected final ReentrantLock readWriteLock = new ReentrantLock();

    protected final PartitionedIndexStorage indexStorage;
    private List<IndexPartition> partitions = new CopyOnWriteArrayList<>();
    private volatile boolean open;

    public AbstractLuceneIndex( PartitionedIndexStorage indexStorage )
    {
        this.indexStorage = indexStorage;
    }

    public void create() throws IOException
    {
        indexStorage.prepareFolder( indexStorage.getIndexFolder() );
        indexStorage.reserveIndexFailureStorage();
        createNewPartitionFolder();
    }

    public void open() throws IOException
    {
        Map<File,Directory> indexDirectories = indexStorage.openIndexDirectories();
        for ( Map.Entry<File,Directory> indexDirectory : indexDirectories.entrySet() )
        {
            partitions.add( new IndexPartition( indexDirectory.getKey(), indexDirectory.getValue() ) );
        }
        open = true;
    }

    boolean isOpen()
    {
        return open;
    }

    public boolean exists() throws IOException
    {
        List<File> folders = indexStorage.listFolders();
        if ( folders.isEmpty() )
        {
            return false;
        }
        for ( File folder : folders )
        {
            if ( !luceneDirectoryExists( folder ) )
            {
                return false;
            }
        }
        return true;
    }

    public boolean isValid()
    {
        if ( open )
        {
            return true;
        }
        Collection<Directory> directories = null;
        try
        {
            directories = indexStorage.openIndexDirectories().values();
            for ( Directory directory : directories )
            {
                // it is ok for index directory to be empty
                // this can happen if it is opened and closed without any writes in between
                if ( !ArrayUtil.isEmpty( directory.listAll() ) )
                {
                    try ( CheckIndex checker = new CheckIndex( directory ) )
                    {
                        CheckIndex.Status status = checker.checkIndex();
                        if ( !status.clean )
                        {
                            return false;
                        }
                    }
                }
            }
        }
        catch ( IOException e )
        {
            return false;
        }
        finally
        {
            IOUtils.closeAllSilently( directories );
        }
        return true;
    }

    public void drop() throws IOException
    {
        close();
        indexStorage.cleanupFolder( indexStorage.getIndexFolder() );
    }

    public void flush() throws IOException
    {
        // TODO: do we need it?
    }

    @Override
    public void close() throws IOException
    {
        commitCloseLock.lock();
        try
        {
            IOUtils.closeAll( partitions );
            partitions.clear();
            open = false;
        }
        finally
        {
            commitCloseLock.unlock();
        }
    }

    public LuceneAllDocumentsReader allDocumentsReader()
    {
        ensureOpen();
        readWriteLock.lock();
        try
        {
            List<PartitionSearcher> searchers = new ArrayList<>( partitions.size() );
            try
            {
                for ( IndexPartition partition : partitions )
                {
                    searchers.add( partition.acquireSearcher() );
                }

                List<LucenePartitionAllDocumentsReader> partitionReaders = searchers.stream()
                        .map( LucenePartitionAllDocumentsReader::new )
                        .collect( toList() );

                return new LuceneAllDocumentsReader( partitionReaders );
            }
            catch ( IOException e )
            {
                IOUtils.closeAllSilently( searchers );
                throw new UncheckedIOException( e );
            }
        }
        finally
        {
            readWriteLock.unlock();
        }
    }

    public ResourceIterator<File> snapshot() throws IOException
    {
        ensureOpen();
        commitCloseLock.lock();
        List<ResourceIterator<File>> snapshotIterators = null;
        try
        {
            List<IndexPartition> partitions = getPartitions();
            snapshotIterators = new ArrayList<>( partitions.size() );
            for ( IndexPartition partition : partitions )
            {
                snapshotIterators.add( partition.snapshot() );
            }
            return Iterables.concatResourceIterators( snapshotIterators.iterator() );
        }
        catch ( Exception e )
        {
            if ( snapshotIterators != null )
            {
                try
                {
                    IOUtils.closeAll( snapshotIterators );
                }
                catch ( IOException ex )
                {
                    throw Exceptions.withCause( ex, e );
                }
            }
            throw e;
        }
        finally
        {
            commitCloseLock.unlock();
        }
    }

    // todo: remove and always use maybeRefreshBlocking?
    public void maybeRefresh() throws IOException
    {
        readWriteLock.lock();
        try
        {
            for ( IndexPartition partition : getPartitions() )
            {
                partition.maybeRefresh();
            }
        }
        finally
        {
            readWriteLock.unlock();
        }
    }

    public void maybeRefreshBlocking() throws IOException
    {
        readWriteLock.lock();
        try
        {
            for ( IndexPartition partition : getPartitions() )
            {
                partition.maybeRefreshBlocking();
            }
        }
        finally
        {
            readWriteLock.unlock();
        }
    }

    List<IndexPartition> getPartitions()
    {
        ensureOpen();
        return partitions;
    }

    IndexPartition addNewPartition() throws IOException
    {
        ensureOpen();
        readWriteLock.lock();
        try
        {
            File partitionFolder = createNewPartitionFolder();
            IndexPartition indexPartition = new IndexPartition( partitionFolder,
                    indexStorage.openDirectory( partitionFolder ) );
            partitions.add( indexPartition );
            return indexPartition;
        }
        finally
        {
            readWriteLock.unlock();
        }
    }

    protected void ensureOpen()
    {
        if ( !open )
        {
            throw new IllegalStateException( "Please open lucene index before working with it." );
        }
    }

    protected boolean hasSinglePartition( List<IndexPartition> partitions )
    {
        return partitions.size() == 1;
    }

    private boolean luceneDirectoryExists( File folder ) throws IOException
    {
        try ( Directory directory = indexStorage.openDirectory( folder ) )
        {
            return DirectoryReader.indexExists( directory );
        }
    }

    private File createNewPartitionFolder() throws IOException
    {
        File partitionFolder = indexStorage.getPartitionFolder( partitions.size() + 1 );
        indexStorage.prepareFolder( partitionFolder );
        return partitionFolder;
    }
}
