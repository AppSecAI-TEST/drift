/*
 * Copyright (C) 2012 ${project.organization.name}
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.airlift.drift.transport.netty;

import io.airlift.drift.TApplicationException;
import io.airlift.drift.TException;
import io.airlift.drift.codec.ThriftCodec;
import io.airlift.drift.codec.ThriftCodecManager;
import io.airlift.drift.protocol.TProtocolReader;

final class ExceptionReader
{
    private static final ThriftCodec<TApplicationException> CODEC =
            new ThriftCodecManager().getCodec(TApplicationException.class);

    private ExceptionReader() {}

    public static TApplicationException readTApplicationException(TProtocolReader protocol)
            throws Exception
    {
        try {
            return CODEC.read(protocol);
        }
        catch (Exception e) {
            if (e instanceof TException) {
                throw e;
            }
            throw new TException(e);
        }
    }
}
