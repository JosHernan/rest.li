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

package com.linkedin.restli.client;

import java.net.URI;
import java.util.Map;

import com.linkedin.restli.common.EmptyRecord;
import com.linkedin.restli.common.PatchRequest;
import com.linkedin.restli.common.ResourceMethod;
import com.linkedin.restli.common.ResourceSpec;
import com.linkedin.restli.internal.client.EmptyResponseDecoder;

/**
 * @author Josh Walker
 * @version $Revision: $
 */

public class PartialUpdateRequest<T>
        extends Request<EmptyRecord>
{
  PartialUpdateRequest(URI uri, PatchRequest<T> input, Map<String, String> headers, ResourceSpec resourceSpec)
  {
    super(uri, ResourceMethod.PARTIAL_UPDATE, input, headers, new EmptyResponseDecoder(), resourceSpec);
  }
}
