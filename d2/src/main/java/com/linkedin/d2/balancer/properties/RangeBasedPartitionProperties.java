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

package com.linkedin.d2.balancer.properties;

import com.linkedin.util.ArgumentUtil;

/**
 * Assumption: every partition has equal size.
 * keyRangeStart is the start point of the keys used by the service; typically it is 0
 * the formula to calculate the partitionId for a key is:
 * (key - keyRangeStart)/partitionSize;
 */

public class RangeBasedPartitionProperties implements PartitionProperties
{
  private final String              _partitionKeyRegex;
  private final long                _keyRangeStart;
  private final long                _partitionSize;
  private final int                 _partitionCount;

  public RangeBasedPartitionProperties(String partitionKeyRegex, long keyRangeStart, long partitionSize, int partitionCount)
  {
    ArgumentUtil.notNull(partitionKeyRegex, "partitionKeyRegex");
    _partitionKeyRegex = partitionKeyRegex;
    _keyRangeStart = keyRangeStart;
    _partitionSize = partitionSize;
    _partitionCount = partitionCount;
  }

  public String getPartitionKeyRegex()
  {
    return _partitionKeyRegex;
  }

  public long getKeyRangeStart()
  {
    return _keyRangeStart;
  }

  public long getPartitionSize()
  {
    return _partitionSize;
  }

  public int getPartitionCount()
  {
    return _partitionCount;
  }

  @Override
  public PartitionType getPartitionType()
  {
    return PartitionType.RANGE;
  }

}
