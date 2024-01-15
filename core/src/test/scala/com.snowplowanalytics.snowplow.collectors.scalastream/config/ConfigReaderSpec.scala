/** Copyright (c) 2014-2023 Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Apache License Version 2.0,
  * and you may not use this file except in compliance with the Apache
  * License Version 2.0.
  * You may obtain a copy of the Apache License Version 2.0 at
  * http://www.apache.org/licenses/LICENSE-2.0.
  *
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the Apache License Version 2.0 is distributed
  * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
  * either express or implied.
  *
  * See the Apache License Version 2.0 for the specific language
  * governing permissions and limitations there under.
  */
package com.snowplowanalytics.snowplow.collectors.scalastream.config

import pureconfig.ConfigSource
import pureconfig.error.ConfigReaderFailure
import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.collectors.scalastream.model.CollectorConfig

class ConfigReaderSpec extends Specification {

  "The collector config reader" should {
    "parse a valid config file" in {
      val source = getConfig("/configs/valid-config.hocon")
      source.load[CollectorConfig] must beRight
    }

    "reject a config file with invalid fallbackDomain" in {
      val source = getConfig("/configs/invalid-fallback-domain.hocon")
      source.load[CollectorConfig] must beLeft.like { case failures =>
        failures.toList must contain { (failure: ConfigReaderFailure) =>
          failure.description must startWith("fallbackDomain contains invalid character")
        }
      }
    }
  }

  def getConfig(resourceName: String): ConfigSource =
    ConfigSource.url(getClass.getResource(resourceName)).withFallback(ConfigSource.default)

}
