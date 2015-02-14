/*
 * Copyright 2015 Google, Inc.
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

package com.netflix.spinnaker.kato.gce.deploy

import com.google.api.services.compute.Compute
import com.google.api.services.compute.model.Operation
import com.netflix.spinnaker.kato.data.task.Task
import java.util.concurrent.TimeUnit

class GCEOperationUtil {
  static class Clock {
    long currentTimeMillis() {
      return System.currentTimeMillis()
    }
  }
  public static OPERATIONS_POLLING_INTERVAL_FRACTION = 5

  private static handleFinishedAsyncDeleteOperation(Operation operation, Task task, String resourceString,
                                                    String basePhase) {
    if (!operation) {
      GCEUtil.updateStatusAndThrowException("Delete operation of $resourceString timed out. The resource " +
          "may still exist.", task, basePhase)
    }
    if (operation.getError()) {
      def error = operation.getError().getErrors().get(0)
      GCEUtil.updateStatusAndThrowException("Failed to delete $resourceString with error: $error", task,
          basePhase)
    }
    task.updateStatus basePhase, "Done deleting $resourceString."
  }

  // The methods below are used to wait on the operation specified in |operationName|. This is used in practice to
  // turn the asynchronous GCE client operations into synchronous calls. Will poll the state of the operation until
  // either state is DONE or |timeoutMillis| is reached.
  static Operation waitForRegionalOperation(Compute compute, String projectName, String region, String operationName,
                                            long timeoutMillis, Task task, String resourceString, String basePhase) {
    return handleFinishedAsyncDeleteOperation(
        waitForOperation({compute.regionOperations().get(projectName, region, operationName).execute()}, timeoutMillis,
                         new GCEOperationUtil.Clock()), task, resourceString, basePhase)
  }

  static Operation waitForGlobalOperation(Compute compute, String projectName, String operationName,
                                          long timeoutMillis, Task task, String resourceString, String basePhase) {
    return handleFinishedAsyncDeleteOperation(
        waitForOperation({compute.globalOperations().get(projectName, operationName).execute()}, timeoutMillis,
                         new GCEOperationUtil.Clock()), task, resourceString, basePhase)
  }

  // TODO(bklingher): implement a more accurate way to achieve timeouts with polling.
  private static Operation waitForOperation(Closure getOperation, long timeoutMillis, Clock clock) {
    def deadline = clock.currentTimeMillis() + timeoutMillis
    long sleepIntervalMillis = timeoutMillis / OPERATIONS_POLLING_INTERVAL_FRACTION
    while (true) {
      Operation operation = getOperation()
      if (operation.getStatus() == "DONE") {
        return operation
      }
      def timeLeftUntilTimeoutMillis = deadline - clock.currentTimeMillis()
      if (timeLeftUntilTimeoutMillis <= 0) {
        break;
      }
      try {
        // TODO(bklingher): Sleeping for timeLeftUntilTimeoutMillis will actually cause us to miss the deadline by one
        // extra polling. We should subtract a constant from it that will cover for the call on the next iteration.
        TimeUnit.MILLISECONDS.sleep(Math.min(sleepIntervalMillis, timeLeftUntilTimeoutMillis))
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return null
  }
}