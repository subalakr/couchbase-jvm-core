/*
 * Copyright (c) 2016 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.core.service;

import com.couchbase.client.core.ResponseEvent;
import com.couchbase.client.core.endpoint.Endpoint;
import com.couchbase.client.core.endpoint.dcp.DCPEndpoint;
import com.couchbase.client.core.env.CoreEnvironment;
import com.lmax.disruptor.RingBuffer;

public class DCPService extends AbstractLazyService {

    /**
     * The endpoint factory.
     */
    private static final EndpointFactory FACTORY = new DCPEndpointFactory();

    public DCPService(String hostname, String bucket, String password, int port, CoreEnvironment env,
                      RingBuffer<ResponseEvent> responseBuffer) {
        super(hostname, bucket, password, port, env, responseBuffer, FACTORY);
    }

    @Override
    public ServiceType type() {
        return ServiceType.DCP;
    }

    static class DCPEndpointFactory implements EndpointFactory {
        @Override
        public Endpoint create(String hostname, String bucket, String password, int port, CoreEnvironment env,
            RingBuffer<ResponseEvent> responseBuffer) {
            return new DCPEndpoint(hostname, bucket, password, port, env, responseBuffer);
        }
    }

}
