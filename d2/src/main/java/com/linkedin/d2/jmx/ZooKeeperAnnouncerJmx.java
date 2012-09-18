/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

/**
 * $Id: $
 */

package com.linkedin.d2.jmx;

import com.linkedin.common.callback.FutureCallback;
import com.linkedin.common.util.None;
import com.linkedin.d2.balancer.properties.PartitionData;
import com.linkedin.d2.balancer.servers.ZooKeeperAnnouncer;
import com.linkedin.d2.discovery.stores.PropertyStoreException;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author Steven Ihde
 * @version $Revision: $
 */

public class ZooKeeperAnnouncerJmx implements ZooKeeperAnnouncerJmxMBean
{
  private final ZooKeeperAnnouncer _announcer;

  public ZooKeeperAnnouncerJmx(ZooKeeperAnnouncer announcer)
  {
    _announcer = announcer;
  }

  @Override
  public void reset() throws PropertyStoreException
  {
    FutureCallback<None> callback = new FutureCallback<None>();
    _announcer.reset(callback);
    try
    {
      callback.get(10, TimeUnit.SECONDS);
    }
    catch (Exception e)
    {
      throw new PropertyStoreException(e);
    }
  }

  @Override
  public void markUp() throws PropertyStoreException
  {
    FutureCallback<None> callback = new FutureCallback<None>();
    _announcer.markUp(callback);
    try
    {
      callback.get(10, TimeUnit.SECONDS);
    }
    catch (Exception e)
    {
      throw new PropertyStoreException(e);
    }
  }

  @Override
  public void markDown() throws PropertyStoreException
  {
    FutureCallback<None> callback = new FutureCallback<None>();
    _announcer.markDown(callback);
    try
    {
      callback.get(10, TimeUnit.SECONDS);
    }
    catch (Exception e)
    {
      throw new PropertyStoreException(e);
    }
  }

  @Override
  public String getCluster()
  {
    return _announcer.getCluster();
  }

  @Override
  public void setCluster(String cluster)
  {
    _announcer.setCluster(cluster);
  }

  @Override
  public String getUri()
  {
    return _announcer.getUri();
  }

  @Override
  public void setUri(String uri)
  {
    _announcer.setUri(uri);
  }

  @Override
  public void setWeight(double weight)
  {
    _announcer.setWeight(weight);
  }

  @Override
  public void setPartitionData(Map<Integer, PartitionData> partitionData)
  {
    _announcer.setPartitionData(partitionData);
  }

  @Override
  public Map<Integer, PartitionData> getPartitionData()
  {
    return _announcer.getPartitionData();
  }
}
