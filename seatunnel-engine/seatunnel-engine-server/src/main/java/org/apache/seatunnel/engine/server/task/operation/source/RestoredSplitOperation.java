/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.engine.server.task.operation.source;

import org.apache.seatunnel.api.source.SourceSplit;
import org.apache.seatunnel.common.utils.RetryUtils;
import org.apache.seatunnel.common.utils.SeaTunnelException;
import org.apache.seatunnel.common.utils.SerializationUtils;
import org.apache.seatunnel.engine.common.Constant;
import org.apache.seatunnel.engine.server.SeaTunnelServer;
import org.apache.seatunnel.engine.server.TaskExecutionService;
import org.apache.seatunnel.engine.server.execution.TaskLocation;
import org.apache.seatunnel.engine.server.serializable.TaskDataSerializerHook;
import org.apache.seatunnel.engine.server.task.SourceSplitEnumeratorTask;
import org.apache.seatunnel.engine.server.task.operation.TaskOperation;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class RestoredSplitOperation extends TaskOperation {

    private byte[] splits;
    private Integer subtaskIndex;

    public RestoredSplitOperation() {
    }

    public RestoredSplitOperation(TaskLocation enumeratorLocation, byte[] splits, int subtaskIndex) {
        super(enumeratorLocation);
        this.splits = splits;
        this.subtaskIndex = subtaskIndex;
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeByteArray(splits);
        out.writeInt(subtaskIndex);
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        splits = in.readByteArray();
        subtaskIndex = in.readInt();
    }

    @Override
    public int getFactoryId() {
        return TaskDataSerializerHook.FACTORY_ID;
    }

    @Override
    public int getClassId() {
        return TaskDataSerializerHook.RESTORED_SPLIT_OPERATOR;
    }

    @Override
    public void run() throws Exception {
        SeaTunnelServer server = getService();
        TaskExecutionService taskExecutionService = server.getTaskExecutionService();
        ClassLoader classLoader = taskExecutionService.getExecutionContext(taskLocation.getTaskGroupLocation()).getClassLoader();

        List<SourceSplit> deserialize = Arrays.stream((Object[]) SerializationUtils.deserialize(splits, classLoader))
            .map(o -> (SourceSplit) o)
            .collect(Collectors.toList());
        RetryUtils.retryWithException(() -> {
            SourceSplitEnumeratorTask<SourceSplit> task = taskExecutionService.getTask(taskLocation);
            task.addSplitsBack(deserialize, subtaskIndex);
            return null;
        }, new RetryUtils.RetryMaterial(Constant.OPERATION_RETRY_TIME, true,
            exception -> exception instanceof SeaTunnelException &&
                !server.taskIsEnded(taskLocation.getTaskGroupLocation()), Constant.OPERATION_RETRY_SLEEP));
    }
}
