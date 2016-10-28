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

package org.apache.spark.scheduler.cluster.cook

import java.util
import java.util.Collections
import java.util.UUID
import scala.collection.JavaConverters._
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.mesos.Protos.Value.Scalar
import org.apache.mesos.Protos._
import org.apache.mesos.{Protos, Scheduler, SchedulerDriver}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.Matchers
import org.scalatest.mock.MockitoSugar
import org.scalatest.BeforeAndAfter

import org.apache.spark.scheduler.TaskSchedulerImpl
import org.apache.spark.{LocalSparkContext, SparkConf, SparkContext, SecurityManager, SparkFunSuite}

import org.apache.spark.scheduler.cluster.cook.CoarseCookSchedulerBackend

import com.twosigma.cook.jobclient.{ JobClient, Job, JobClientException }

class CoarseCookSchedulerBackendSuite extends SparkFunSuite
    with LocalSparkContext
    with MockitoSugar
    with BeforeAndAfter {

  private def createSchedulerBackend(taskScheduler: TaskSchedulerImpl): CoarseCookSchedulerBackend = {
    val backend = new CoarseCookSchedulerBackend(
      taskScheduler, sc, "127.0.0.1", 12321, "vagrant", "ignorePassword"
    )
    backend.start()
    backend
  }

  private def getJobs(backend: CoarseCookSchedulerBackend): List[Job] = {
    backend.jobClient.query(backend.runningJobUUIDs.asJavaCollection).asScala.values.toList
  }

  before {
    val sparkConf = (new SparkConf)
      .setMaster("local[*]")
      .setAppName("test-cook-dynamic-alloc")
      .setSparkHome("/path")

    sparkConf.set("spark.executor.failures", "2")
    sparkConf.set("spark.cook.priority", "50")
    sparkConf.set("spark.cores.max", "3")
    sparkConf.set("spark.cook.cores.per.job.max", "1")

    sc = new SparkContext(sparkConf)
  }

  test("isReady") {
    val taskScheduler = mock[TaskSchedulerImpl]
    when(taskScheduler.sc).thenReturn(sc)

    val backend = createSchedulerBackend(taskScheduler)

    assert(backend.isReady())
  }

  test("initial executors on start-up") {
    val taskScheduler = mock[TaskSchedulerImpl]
    when(taskScheduler.sc).thenReturn(sc)

    val backend = createSchedulerBackend(taskScheduler)

    assert(backend.runningJobUUIDs.size == 3)
    assert(backend.currentCoresLimit == 0)
    assert(backend.abortedJobIds.isEmpty)
  }

  test("cook sets custom priority for jobs") {
    val taskScheduler = mock[TaskSchedulerImpl]
    when(taskScheduler.sc).thenReturn(sc)

    val backend = createSchedulerBackend(taskScheduler)
    var jobs = getJobs(backend)

    assert(jobs(0).getPriority() == 50)
    assert(jobs(1).getPriority() == 50)
    assert(jobs(2).getPriority() == 50)
  }

  test("cook supports scaling executors up & down") {
    val taskScheduler = mock[TaskSchedulerImpl]
    when(taskScheduler.sc).thenReturn(sc)

    val backend = createSchedulerBackend(taskScheduler)
    var executorIds = backend.runningJobUUIDs.map(_.toString).toSeq

    backend.doKillExecutors(executorIds)
    assert(backend.abortedJobIds == backend.runningJobUUIDs)

    var jobs = getJobs(backend)

    // force job status update
    for (job <- jobs) backend.jobListener.onStatusUpdate(job)

    assert(backend.abortedJobIds.isEmpty)
    assert(backend.runningJobUUIDs.size == 0)

    backend.doRequestTotalExecutors(0).onComplete{
      case Success(value) => assert(value)
      case Failure(e) => e.printStackTrace()
    }
    assert(backend.totalFailures == 0)

    backend.doRequestTotalExecutors(2).onComplete{
      case Success(value) => assert(value)
      case Failure(e) => e.printStackTrace();
    }

    assert(backend.totalFailures == 0)

    assert(backend.runningJobUUIDs.size == 2)
    assert(backend.currentCoresLimit == 0)

    executorIds = backend.runningJobUUIDs.map(_.toString).toSeq
    backend.doKillExecutors(executorIds)

    backend.doRequestTotalExecutors(1).onComplete{
      case Success(value) => assert(value)
      case Failure(e) => e.printStackTrace()
    }
    assert(backend.totalFailures == 0)

    jobs = getJobs(backend)

    // force job status update
    for (job <- jobs) backend.jobListener.onStatusUpdate(job)

    assert(backend.currentCoresLimit == 1)
    assert(backend.abortedJobIds.isEmpty)

    backend.requestRemainingCores()

    assert(backend.currentCoresLimit == 0)
    assert(backend.runningJobUUIDs.size == 1)
  }

  test("cook requests new jobs when failure limit was not exceeded") {
    val taskScheduler = mock[TaskSchedulerImpl]
    when(taskScheduler.sc).thenReturn(sc)

    val backend = createSchedulerBackend(taskScheduler)
    var executorIds = backend.runningJobUUIDs.map(_.toString).toSeq

    backend.doKillExecutors(executorIds)
    assert(backend.abortedJobIds == backend.runningJobUUIDs)

    var jobs = getJobs(backend)

    // force job status update and simulate that one of the jobs wasn't aborted via dyn-alloc
    backend.abortedJobIds -= jobs.head.getUUID
    for (job <- jobs) backend.jobListener.onStatusUpdate(job)

    assert(backend.abortedJobIds.isEmpty)
    assert(backend.runningJobUUIDs.size == 1)
    assert(backend.currentCoresLimit == 2)
  }

  test("cook doesn't request new jobs when failure limit was exceeded") {
    val taskScheduler = mock[TaskSchedulerImpl]
    when(taskScheduler.sc).thenReturn(sc)

    val backend = createSchedulerBackend(taskScheduler)
    var executorIds = backend.runningJobUUIDs.map(_.toString).toSeq

    backend.doKillExecutors(executorIds)
    assert(backend.abortedJobIds == backend.runningJobUUIDs)

    // force job status update and simulate that two jobs weren't aborted via dyn-alloc
    var jobs = getJobs(backend)
    backend.abortedJobIds -= jobs(0).getUUID
    backend.abortedJobIds -= jobs(2).getUUID
    for (job <- jobs) backend.jobListener.onStatusUpdate(job)

    assert(backend.abortedJobIds.isEmpty)
    assert(backend.runningJobUUIDs.size == 1)
    assert(backend.totalFailures == 2)
    assert(backend.currentCoresLimit == 2)

    executorIds = backend.runningJobUUIDs.map(_.toString).toSeq
    backend.doKillExecutors(executorIds)

    // force job status update and simulate that none of the jobs was aborted via dyn-alloc
    jobs = getJobs(backend)
    backend.abortedJobIds = Set()
    for (job <- jobs) backend.jobListener.onStatusUpdate(job)

    assert(backend.abortedJobIds.isEmpty)
    assert(backend.runningJobUUIDs.isEmpty)
    assert(backend.totalFailures == 3)
    assert(backend.currentCoresLimit == 3)

    backend.requestRemainingCores()

    assert(backend.runningJobUUIDs.size == 3)
  }

  test("cook doesn't update aborted-jobs when aborting a job fails") {
    val jobId = UUID.randomUUID()

    val taskScheduler = mock[TaskSchedulerImpl]
    when(taskScheduler.sc).thenReturn(sc)

    val jobClientMock = mock[JobClient]
    when(jobClientMock.abort(List(jobId).asJavaCollection)).thenThrow(mock[JobClientException])

    val backend = new CoarseCookSchedulerBackend(
      taskScheduler, sc, "127.0.0.1", 12321, "vagrant", "ignorePassword"
    ) {
      override val jobClient = jobClientMock
    }
    backend.doKillExecutors(Seq(jobId.toString)).onComplete{
      case Success(value) => assert(!value)
      case Failure(err) => err.printStackTrace()
    }
    assert(!backend.abortedJobIds.contains(jobId))
  }
}
