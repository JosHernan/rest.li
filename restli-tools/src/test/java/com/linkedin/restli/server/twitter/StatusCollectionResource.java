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

package com.linkedin.restli.server.twitter;

import com.linkedin.restli.common.PatchRequest;
import com.linkedin.restli.server.CreateResponse;
import com.linkedin.restli.server.PagingContext;
import com.linkedin.restli.server.ResourceLevel;
import com.linkedin.restli.server.UpdateResponse;
import com.linkedin.restli.server.annotations.Action;
import com.linkedin.restli.server.annotations.ActionParam;
import com.linkedin.restli.server.annotations.Context;
import com.linkedin.restli.server.annotations.Finder;
import com.linkedin.restli.server.annotations.Optional;
import com.linkedin.restli.server.annotations.QueryParam;
import com.linkedin.restli.server.annotations.RestLiCollection;
import com.linkedin.restli.server.resources.CollectionResourceTemplate;
import com.linkedin.restli.server.twitter.TwitterTestDataModels.Status;
import com.linkedin.restli.server.twitter.TwitterTestDataModels.StatusType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CollectionResource containing all statuses
 *
 * @author dellamag
 */
@RestLiCollection(name="statuses",
                    keyName="statusID")
public class StatusCollectionResource extends CollectionResourceTemplate<Long,Status>
{
  /**
   * Gets a sample of the timeline of statuses generated by all users
   */
  @Finder("public_timeline")
  public List<Status> getPublicTimeline(@Context PagingContext pagingContext)
  {
    return null;
  }

  /**
   * Gets the status timeline for a given user
   */
  @Finder("user_timeline")
  public List<Status> getUserTimeline(@Context PagingContext pagingContext)
  {
    return null;
  }

  /**
   * Keyword search for statuses
   *
   * @param keywords keywords to search for
   * @param since a unix timestamp. If present, only statuses created after this time are returned
   */
  @Finder("search")
  public List<Status> search(@Context PagingContext pagingContext,
                             @QueryParam("keywords") String keywords,
                             @QueryParam("since") @Optional("-1") long since,
                             @QueryParam("type") @Optional StatusType type)
  {
    return null;
  }


  /**
   * Creates a new Status
   */
  @Override
  public CreateResponse create(Status entity)
  {
    return null;
  }

  /**
   * Gets a batch of statuses
   */
  @Override
  public Map<Long, Status> batchGet(Set<Long> ids)
  {
    return null;
  }

  /**
   * Gets a single status resource
   */
  @Override
  public Status get(Long key)
  {
    return null;
  }

  /**
   * Deletes a status resource
   */
  @Override
  public UpdateResponse delete(Long key)
  {
    return null;
  }

  /**
   * Updates a single status resource
   */
  @Override
  public UpdateResponse update(Long key, PatchRequest<Status> request)
  {
    return null;
  }

  /**
   * Ambiguous action binding test case
   */
  @Action(name="forward",
          resourceLevel= ResourceLevel.ENTITY)
  public void forward(@ActionParam("to") long userID)
  {
  }
}
