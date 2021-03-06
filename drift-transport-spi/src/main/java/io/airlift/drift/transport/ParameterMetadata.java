/*
 * Copyright (C) 2013 Facebook, Inc.
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
package io.airlift.drift.transport;

import io.airlift.drift.codec.ThriftCodec;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public final class ParameterMetadata
{
    private final short id;
    private final String name;
    private final ThriftCodec<Object> codec;

    public ParameterMetadata(short id, String name, ThriftCodec<Object> codec)
    {
        checkArgument(id >= 0, "id is negative");
        this.id = id;
        this.name = requireNonNull(name, "name is null");
        this.codec = requireNonNull(codec, "codec is null");
    }

    public short getId()
    {
        return id;
    }

    public String getName()
    {
        return name;
    }

    public ThriftCodec<Object> getCodec()
    {
        return codec;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .toString();
    }
}
