/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs;

import java.io.IOException;
import java.io.RandomAccessFile;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.FSDataset;
import org.apache.hadoop.hdfs.server.protocol.BlockMetaDataInfo;

/** This class implements some of tests posted in HADOOP-2658. */
public class TestMNFileAppend3 extends junit.framework.TestCase {
  static final long BLOCK_SIZE = 64 * 1024;
  static final short REPLICATION = 3;
  static final int DATANODE_NUM = 5;

  private static Configuration conf;
  private static int buffersize;
  private static MiniMNDFSCluster cluster;
  private static DistributedFileSystem fs;
  
  protected void setUp() throws java.lang.Exception {
      SNAppendTestUtil.LOG.info("setUp()");
      conf = new Configuration();
      conf.setInt("io.bytes.per.checksum", 512);
      conf.setBoolean("dfs.support.append", true);
      conf.setStrings("dfs.namenode.port.list", "0,0");
      buffersize = conf.getInt("io.file.buffer.size", 4096);
      cluster = new MiniMNDFSCluster(conf, DATANODE_NUM, 0, true, null);
      cluster.waitDatanodeDie();
      fs = (DistributedFileSystem)cluster.getFileSystem(1);
    }
  
    protected void tearDown() throws Exception {
      SNAppendTestUtil.LOG.info("tearDown()");
      if(fs != null) fs.close();
      if(cluster != null) cluster.shutdown();
    }

  /** TC1: Append on block boundary. */
  public void testTC1() throws Exception {
    final Path p = new Path("/TC1/foo");
    System.out.println("p=" + p);

    //a. Create file and write one block of data. Close file.
    final int len1 = (int)BLOCK_SIZE; 
    {
      FSDataOutputStream out = fs.create(p, false, buffersize, REPLICATION, BLOCK_SIZE);
      SNAppendTestUtil.write(out, 0, len1);
      out.close();
    }

    //   Reopen file to append. Append half block of data. Close file.
    final int len2 = (int)BLOCK_SIZE/2; 
    {
      FSDataOutputStream out = fs.append(p);
      SNAppendTestUtil.write(out, len1, len2);
      out.close();
    }
    
    //b. Reopen file and read 1.5 blocks worth of data. Close file.
    SNAppendTestUtil.check(fs, p, len1 + len2);
  }

  /** TC2: Append on non-block boundary. */
  public void testTC2() throws Exception {
    final Path p = new Path("/TC2/foo");
    System.out.println("p=" + p);

    //a. Create file with one and a half block of data. Close file.
    final int len1 = (int)(BLOCK_SIZE + BLOCK_SIZE/2); 
    {
      FSDataOutputStream out = fs.create(p, false, buffersize, REPLICATION, BLOCK_SIZE);
      SNAppendTestUtil.write(out, 0, len1);
      out.close();
    }

    //   Reopen file to append quarter block of data. Close file.
    final int len2 = (int)BLOCK_SIZE/4; 
    {
      FSDataOutputStream out = fs.append(p);
      SNAppendTestUtil.write(out, len1, len2);
      out.close();
    }

    //b. Reopen file and read 1.75 blocks of data. Close file.
    SNAppendTestUtil.check(fs, p, len1 + len2);
  }

  /** TC5: Only one simultaneous append. */
  public void testTC5() throws Exception {
    final Path p = new Path("/TC5/foo");
    System.out.println("p=" + p);

    //a. Create file on Machine M1. Write half block to it. Close file.
    {
      FSDataOutputStream out = fs.create(p, false, buffersize, REPLICATION, BLOCK_SIZE);
      SNAppendTestUtil.write(out, 0, (int)(BLOCK_SIZE/2));
      out.close();
    }

    //b. Reopen file in "append" mode on Machine M1.
    FSDataOutputStream out = fs.append(p);

    //c. On Machine M2, reopen file in "append" mode. This should fail.
    try {
      SNAppendTestUtil.createHdfsWithDifferentUsername(conf).append(p);
      fail("This should fail.");
    } catch(IOException ioe) {
      SNAppendTestUtil.LOG.info("GOOD: got an exception", ioe);
    }

    //d. On Machine M1, close file.
    out.close();        
  }

  /** TC7: Corrupted replicas are present. */
  public void testTC7() throws Exception {
    final short repl = 2;
    final Path p = new Path("/TC7/foo");
    System.out.println("p=" + p);
    
    //a. Create file with replication factor of 2. Write half block of data. Close file.
    final int len1 = (int)(BLOCK_SIZE/2); 
    {
      FSDataOutputStream out = fs.create(p, false, buffersize, repl, BLOCK_SIZE);
      SNAppendTestUtil.write(out, 0, len1);
      out.close();
    }
    DFSTestUtil.waitReplication(fs, p, repl);

    //b. Log into one datanode that has one replica of this block.
    //   Find the block file on this datanode and truncate it to zero size.
    final LocatedBlocks locatedblocks = fs.dfs.namenode.getBlockLocations(p.toString(), 0L, len1);
    assertEquals(1, locatedblocks.locatedBlockCount());
    final LocatedBlock lb = locatedblocks.get(0);
    final Block blk = lb.getBlock();
    assertEquals(len1, lb.getBlockSize());

    DatanodeInfo[] datanodeinfos = lb.getLocations();
    assertEquals(repl, datanodeinfos.length);
    final DataNode dn = cluster.getDataNode(datanodeinfos[0].getIpcPort());
    final FSDataset data = (FSDataset)dn.getFSDataset();
    final RandomAccessFile raf = new RandomAccessFile(data.getBlockFile(blk), "rw");
    SNAppendTestUtil.LOG.info("dn=" + dn + ", blk=" + blk + " (length=" + blk.getNumBytes() + ")");
    assertEquals(len1, raf.length());
    raf.setLength(0);
    raf.close();

    //c. Open file in "append mode".  Append a new block worth of data. Close file.
    final int len2 = (int)BLOCK_SIZE; 
    {
      FSDataOutputStream out = fs.append(p);
      SNAppendTestUtil.write(out, len1, len2);
      out.close();
    }

    //d. Reopen file and read two blocks worth of data.
    SNAppendTestUtil.check(fs, p, len1 + len2);
  }

  /** TC11: Racing rename */
  public void testTC11() throws Exception {
    final Path p = new Path("/TC11/foo");
    System.out.println("p=" + p);

    //a. Create file and write one block of data. Close file.
    final int len1 = (int)BLOCK_SIZE; 
    {
      FSDataOutputStream out = fs.create(p, false, buffersize, REPLICATION, BLOCK_SIZE);
      SNAppendTestUtil.write(out, 0, len1);
      out.close();
    }

    //b. Reopen file in "append" mode. Append half block of data.
    FSDataOutputStream out = fs.append(p);
    final int len2 = (int)BLOCK_SIZE/2; 
    SNAppendTestUtil.write(out, len1, len2);
    
    //c. Rename file to file.new.
    final Path pnew = new Path(p + ".new");
    assertTrue(fs.rename(p, pnew));

    //d. Close file handle that was opened in (b). 
    try {
      out.close();
      fail("close() should throw an exception");
    } catch(Exception e) {
      SNAppendTestUtil.LOG.info("GOOD!", e);
    }

    //wait for the lease recovery 
    cluster.setLeasePeriod(1000, 1000);
    SNAppendTestUtil.sleep(5000);

    //check block sizes 
    final long len = fs.getFileStatus(pnew).getLen();
    final LocatedBlocks locatedblocks = fs.dfs.namenode.getBlockLocations(pnew.toString(), 0L, len);
    final int numblock = locatedblocks.locatedBlockCount();
    for(int i = 0; i < numblock; i++) {
      final LocatedBlock lb = locatedblocks.get(i);
      final Block blk = lb.getBlock();
      final long size = lb.getBlockSize();
      if (i < numblock - 1) {
        assertEquals(BLOCK_SIZE, size);
      }
      for(DatanodeInfo datanodeinfo : lb.getLocations()) {
        final DataNode dn = cluster.getDataNode(datanodeinfo.getIpcPort());
        final BlockMetaDataInfo metainfo = dn.getBlockMetaDataInfo(blk);
        assertEquals(size, metainfo.getNumBytes());
      }
    }
  }

  /** TC12: Append to partial CRC chunk */
  public void testTC12() throws Exception {
    final Path p = new Path("/TC12/foo");
    System.out.println("p=" + p);
    cluster.setLeasePeriod(1000, FSConstants.LEASE_HARDLIMIT_PERIOD);
    //a. Create file with a block size of 64KB
    //   and a default io.bytes.per.checksum of 512 bytes.
    //   Write 25687 bytes of data. Close file.
    final int len1 = 25687; 
    {
      FSDataOutputStream out = fs.create(p, false, buffersize, REPLICATION, BLOCK_SIZE);
      SNAppendTestUtil.write(out, 0, len1);
      out.close();
    }

    //b. Reopen file in "append" mode. Append another 5877 bytes of data. Close file.
    final int len2 = 5877; 
    {
      FSDataOutputStream out = fs.append(p);
      SNAppendTestUtil.write(out, len1, len2);
      out.close();
    }

    //c. Reopen file and read 25687+5877 bytes of data from file. Close file.
    SNAppendTestUtil.check(fs, p, len1 + len2);
  }
}
