/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.containerpool.yarn.test

import akka.actor.ActorSystem
import akka.http.scaladsl.model.DateTime
import org.apache.openwhisk.common.{PrintStreamLogging, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.WhiskConfig._
import org.apache.openwhisk.core.containerpool.ContainerArgsConfig
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.entity.{ByteSize, ExecManifest, SizeUnits}
import org.apache.openwhisk.core.yarn.YARNServiceActor._
import org.apache.openwhisk.core.yarn.{YARNConfig, YARNContainerFactory}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpecLike, Suite}

import scala.collection.immutable.Map
import scala.concurrent.Await
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class YARNContainerFactoryTests extends Suite with BeforeAndAfter with FlatSpecLike {

  val images = Array(
    ImageName("nodejs6action", Option("openwhisk"), Option("latest")),
    ImageName("python3action", Option("openwhisk"), Option("latest")))

  val runtimes: String = "{\"runtimes\":{" +
    "\"nodejs\":[{\"kind\":\"nodejs:6\",\"image\":{\"prefix\":\"openwhisk\",\"name\":\"nodejs6action\",\"tag\":\"latest\"}}]," +
    "\"python\":[{\"kind\":\"python:3\",\"image\":{\"prefix\":\"openwhisk\",\"name\":\"python3action\",\"tag\":\"latest\"}}]" +
    "}}"

  implicit val logging: PrintStreamLogging = new PrintStreamLogging()
  implicit val whiskConfig: WhiskConfig = new WhiskConfig(
    Map(wskApiHostname -> "apihost", runtimesManifest -> runtimes) ++ wskApiHost)

  val containerArgsConfig =
    ContainerArgsConfig("net1", Seq("dns1", "dns2"), Map("extra1" -> Set("e1", "e2"), "extra2" -> Set("e3", "e4")))

  val yarnConfig =
    YARNConfig(
      "http://localhost:8088",
      yarnLinkLogMessage = true,
      "openwhisk-action-service",
      SIMPLEAUTH,
      "",
      "",
      "default",
      "256",
      1)

  //System.setProperty("java.security.auth.login.config", "~/login.conf")
  //System.setProperty("java.security.krb5.conf", "/etc/krb5.conf")

  val properties: Map[String, Set[String]] = Map[String, Set[String]]()
  ExecManifest.initialize(whiskConfig)

  behavior of "YARNContainerFactory"

  it should "initalize correctly with zero containers" in {

    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory =
      new YARNContainerFactory(whiskConfig, ActorSystem(), logging, properties, containerArgsConfig, yarnConfig)
    factory.init()

    //Service was created
    assert(rm.services.contains(yarnConfig.serviceName))

    //Factory waited until service was stable
    assert(rm.initCompletionTimes.getOrElse(yarnConfig.serviceName, DateTime.MaxValue) < DateTime.now)

    //No containers were created
    assert(rm.flexCompletionTimes.getOrElse(yarnConfig.serviceName, Map[String, DateTime]()).isEmpty)

    val componentNamesInService = rm.services.get(yarnConfig.serviceName).orNull.components.map(c => c.name)
    val missingImages = images
      .map(e => e.name)
      .filter(imageName => !componentNamesInService.contains(imageName))

    //All images types were created
    assert(missingImages.isEmpty)

    //All components have zero containers
    assert(rm.services.get(yarnConfig.serviceName).orNull.components.forall(c => c.number_of_containers == 0))

    rm.stop()
  }

  it should "create a container" in {
    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory =
      new YARNContainerFactory(whiskConfig, ActorSystem(), logging, properties, containerArgsConfig, yarnConfig)
    factory.init()

    val imageToCreate = images(0)
    val containerFuture = factory.createContainer(
      TransactionId.testing,
      "name",
      imageToCreate,
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    Await.result(containerFuture, 60.seconds)

    //Container of the correct type was created
    assert(rm.services.contains(yarnConfig.serviceName))
    assert(
      rm.services
        .get(yarnConfig.serviceName)
        .orNull
        .components
        .find(c => c.name.equals(imageToCreate.name))
        .orNull != null)
    assert(
      rm.services
        .get(yarnConfig.serviceName)
        .orNull
        .components
        .find(c => c.name.equals(imageToCreate.name))
        .orNull
        .number_of_containers == 1)

    //Factory waited for container to be stable
    assert(
      rm.flexCompletionTimes
        .getOrElse(yarnConfig.serviceName, Map[String, DateTime]())
        .getOrElse(imageToCreate.name, DateTime.MaxValue) < DateTime.now)

    rm.stop()
  }

  it should "destroy a container" in {
    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory =
      new YARNContainerFactory(whiskConfig, ActorSystem(), logging, properties, containerArgsConfig, yarnConfig)
    factory.init()

    val imageToCreate = images(0)
    val containerFuture = factory.createContainer(
      TransactionId.testing,
      "name",
      imageToCreate,
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    val container = Await.result(containerFuture, 30.seconds)
    val destroyFuture = container.destroy()(TransactionId.testing)
    Await.result(destroyFuture, 30.seconds)

    //Container of the correct type was deleted
    assert(rm.services.contains(yarnConfig.serviceName))
    assert(
      rm.services
        .get(yarnConfig.serviceName)
        .orNull
        .components
        .find(c => c.name.equals(imageToCreate.name))
        .orNull != null)
    assert(
      rm.services
        .get(yarnConfig.serviceName)
        .orNull
        .components
        .find(c => c.name.equals(imageToCreate.name))
        .orNull
        .number_of_containers == 0)

    //no need to wait for stability
    rm.stop()
  }
  it should "create and destroy multiple containers" in {
    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory =
      new YARNContainerFactory(whiskConfig, ActorSystem(), logging, properties, containerArgsConfig, yarnConfig)
    factory.init()

    val container1Future = factory.createContainer(
      TransactionId.testing,
      "name",
      images(0),
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    val container2Future = factory.createContainer(
      TransactionId.testing,
      "name",
      images(1),
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    val container3Future = factory.createContainer(
      TransactionId.testing,
      "name",
      images(0),
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    Await.result(container1Future, 30.seconds)
    val container2 = Await.result(container2Future, 30.seconds)
    val container3 = Await.result(container3Future, 30.seconds)

    val destroyFuture1 = container2.destroy()(TransactionId.testing)
    Await.result(destroyFuture1, 30.seconds)

    val destroyFuture2 = container3.destroy()(TransactionId.testing)
    Await.result(destroyFuture2, 30.seconds)

    //Containers of the correct type was deleted
    assert(rm.services.contains(yarnConfig.serviceName))
    assert(
      rm.services.get(yarnConfig.serviceName).orNull.components.find(c => c.name.equals(images(1).name)).orNull != null)
    assert(
      rm.services
        .get(yarnConfig.serviceName)
        .orNull
        .components
        .find(c => c.name.equals(images(1).name))
        .orNull
        .number_of_containers == 0)

    assert(
      rm.services.get(yarnConfig.serviceName).orNull.components.find(c => c.name.equals(images(0).name)).orNull != null)
    assert(
      rm.services
        .get(yarnConfig.serviceName)
        .orNull
        .components
        .find(c => c.name.equals(images(0).name))
        .orNull
        .number_of_containers == 1)

    //Factory waited for container to be stable
    assert(
      rm.flexCompletionTimes
        .getOrElse(yarnConfig.serviceName, Map[String, DateTime]())
        .getOrElse(images(0).name, DateTime.MaxValue) < DateTime.now)

    rm.stop()
  }
  it should "cleanup" in {
    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory =
      new YARNContainerFactory(whiskConfig, ActorSystem(), logging, properties, containerArgsConfig, yarnConfig)
    factory.init()
    factory.cleanup()

    //Service was destroyed
    assert(!rm.services.contains(yarnConfig.serviceName))
    assert(!rm.initCompletionTimes.contains(yarnConfig.serviceName))
    assert(!rm.flexCompletionTimes.contains(yarnConfig.serviceName))

    rm.stop()
  }
}
