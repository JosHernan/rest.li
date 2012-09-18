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

package com.linkedin.restli.client;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.linkedin.data.template.RecordTemplate;
import com.linkedin.r2.RemoteInvocationException;


/**
 * Exposes the response from a REST operation to the client.
 *
 * @param <T> response entity template class
 *
 * @author Eran Leshem
 */
public interface ResponseFuture<T> extends Future<Response<T>>
{
  Response<T> getResponse() throws RemoteInvocationException;

  Response<T> getResponse(long timeout, TimeUnit unit) throws RemoteInvocationException, TimeoutException;
}
