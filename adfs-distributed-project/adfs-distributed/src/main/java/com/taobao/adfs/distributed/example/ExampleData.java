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

package com.taobao.adfs.distributed.example;

import java.io.IOException;
import java.io.RandomAccessFile;

import org.apache.hadoop.conf.Configuration;

import com.taobao.adfs.distributed.DistributedData;
import com.taobao.adfs.util.ReentrantReadWriteLockExtension;
import com.taobao.adfs.util.Utilities;

/**
 * @author <a href=mailto:zhangwei.yangjie@gmail.com/jiwan@taobao.com>zhangwei/jiwan</a>
 * @created 2011-05-17
 */
public class ExampleData extends DistributedData implements ExampleProtocol {
  RandomAccessFile fileForDataContent = null;

  public ExampleData(Configuration conf, ReentrantReadWriteLockExtension getDataLocker) throws IOException {
    super(getDataLocker);
    this.conf = (conf == null) ? new Configuration(false) : conf;
    initialize();
  }

  @Override
  synchronized public void open() throws IOException {
    try {
      super.open();
    } catch (Throwable t) {
      setDataVersion(-1);
      throw new IOException(t);
    }
    fileForDataContent = new RandomAccessFile(getDataPath() + "/" + "content.txt", "rwd");
    if (conf.getBoolean("distributed.data.format", false)) {
      format();
      conf.setBoolean("distributed.data.format", false);
    }
  }

  public boolean isValid() {
    try {
      read();
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  // implements ExampleProtocol interface

  @Override
  public Object[] format() throws IOException {
    try {
      Utilities.logInfo(logger, "data is formating");
      super.format();
      fileForDataContent.setLength(0);
      fileForDataContent.writeLong(version.get());
      Utilities.logInfo(logger, "data is formatted");
      return new Object[0];
    } catch (Throwable t) {
      setDataVersion(-1);
      throw new IOException(t);
    }
  }

  @Override
  synchronized public String write(String content) throws IOException {
    fileForDataContent.setLength(0);
    fileForDataContent.writeLong(version.increaseAndGet());
    fileForDataContent.writeBytes(content);
    return content;
  }

  @Override
  public Object writeRequestInMemory(Object object) throws IOException {
    return object;
  }

  @Override
  public Object writeResultInMemory(Object object) throws IOException {
    return object;
  }

  @Override
  public Object readInMemory(Object object) throws IOException {
    return object;
  }

  @Override
  synchronized public String read() throws IOException {
    fileForDataContent.seek(0);
    fileForDataContent.readLong();
    String content = "";
    while (true) {
      String line = fileForDataContent.readLine();
      if (line == null) break;
      content += line;
    }
    return content;
  }

  // for DistributedData

  public ExampleData() {
  }

  @Override
  public DistributedData getDataAll(DistributedData data, ReentrantReadWriteLockExtension.WriteLock writeLock)
      throws IOException {
    // block write requests
    Utilities.logInfo(logger, conf.get("distributed.server.name"), " prepare to block invocation for get all data");
    writeLock.lock();
    Utilities.logInfo(logger, conf.get("distributed.server.name"), " already blocked invocation for get all data");
    data.putElementToTransfer("distributed.data.version", getDataVersion());
    data.putElementToTransfer("distributed.data.content", read());
    return data;
  }

  @Override
  public void setDataAll(DistributedData data) throws IOException {
    write((String) data.getElementToTransfer("distributed.data.content"));
    setDataVersion((Long) data.getElementToTransfer("distributed.data.version"));
  }

  @Override
  public boolean lock(long expireTime, long timeout, Object... objects) throws IOException {
    return lock(null, expireTime, timeout, objects) != null;
  }

  @Override
  public boolean tryLock(long expireTime, Object... objects) throws IOException {
    return tryLock(null, expireTime, objects) != null;
  }

  @Override
  public boolean unlock(Object... objects) throws IOException {
    return unlock(null, objects) != null;
  }
}
