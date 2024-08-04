/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd.
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
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import org.apache.commons.codec.binary.Base64

class StdoutSink(val maxBytes: Int, streamName: String) extends Sink {

  // Print a Base64-encoded event.
  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    streamName match {
      case "out" =>
        println(s"StdoutSink.out: ${events.size}")
        events.foreach { e =>
          val decoded = new com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
          val decoder = new org.apache.thrift.TDeserializer
          decoder.deserialize(decoded, e)

          /** must be
            * {"schema":"iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4","data":[{"e":"pv","url":"https://33d2-85-152-106-87.ngrok-free.app/snowplow-segment-valid.html","page":"Fingerprint Inspection","eid":"8aa86931-3db3-4517-b77f-3ff6feb21e3a","tv":"js-3.4.0","tna":"sp","aid":"yourappid","p":"web","cookie":"1","cs":"windows-1252","lang":"en-US","res":"1512x982","cd":"30","tz":"Europe/Madrid","dtm":"1719229281942","vp":"1512x422","ds":"1512x422","vid":"1","sid":"34f4107b-23d4-4d74-804e-e08793e9afc1","duid":"c2e619fa-1462-47b3-b677-d6b27abd42f5","cx":"eyJzY2hlbWEiOiJpZ2x1OmNvbS5zbm93cGxvd2FuYWx5dGljcy5zbm93cGxvdy9jb250ZXh0cy9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6W3sic2NoZW1hIjoiaWdsdTpjb20uc2VnbWVudC9hbmFseXRpY3Nqcy9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6eyJwYXlsb2FkIjp7InRpbWVzdGFtcCI6IjIwMjQtMDYtMDVUMDU6MTU6MTguMTA5WiIsImludGVncmF0aW9ucyI6e30sInR5cGUiOiJwYWdlIiwicHJvcGVydGllcyI6eyJwYXRoIjoiL3NlZ21lbnQuaHRtbCIsInJlZmVycmVyIjoiIiwic2VhcmNoIjoiIiwidGl0bGUiOiJGaW5nZXJwcmludCBJbnNwZWN0aW9uIiwidXJsIjoiaHR0cHM6Ly9wcmV2aWV3LnNub3djYXRjbG91ZC5jb20vc2VnbWVudC5odG1sIn0sImNvbnRleHQiOnsicGFnZSI6eyJwYXRoIjoiL3NlZ21lbnQuaHRtbCIsInJlZmVycmVyIjoiIiwic2VhcmNoIjoiIiwidGl0bGUiOiJGaW5nZXJwcmludCBJbnNwZWN0aW9uIiwidXJsIjoiaHR0cHM6Ly9wcmV2aWV3LnNub3djYXRjbG91ZC5jb20vc2VnbWVudC5odG1sIn0sInVzZXJBZ2VudCI6Ik1vemlsbGEvNS4wIChNYWNpbnRvc2g7IEludGVsIE1hYyBPUyBYIDEwXzE1XzcpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIENocm9tZS8xMjUuMC4wLjAgU2FmYXJpLzUzNy4zNiIsInVzZXJBZ2VudERhdGEiOnsiYnJhbmRzIjpbeyJicmFuZCI6Ikdvb2dsZSBDaHJvbWUiLCJ2ZXJzaW9uIjoiMTI1In0seyJicmFuZCI6IkNocm9taXVtIiwidmVyc2lvbiI6IjEyNSJ9LHsiYnJhbmQiOiJOb3QuQS9CcmFuZCIsInZlcnNpb24iOiIyNCJ9XSwibW9iaWxlIjpmYWxzZSwicGxhdGZvcm0iOiJtYWNPUyJ9LCJsb2NhbGUiOiJlbi1VUyIsImxpYnJhcnkiOnsibmFtZSI6ImFuYWx5dGljcy5qcyIsInZlcnNpb24iOiJuZXh0LTEuNzAuMCJ9LCJ0aW1lem9uZSI6IkFtZXJpY2EvTG9zX0FuZ2VsZXMifSwibWVzc2FnZUlkIjoiYWpzLW5leHQtMTcxNzU2NDUxODEwOS03MWNkOGRjNS00ZTM1LTQ1MjAtODgxOC03ODRmZDU0YmJlODQiLCJhbm9ueW1vdXNJZCI6IjUwZGQ3ZmZmLWE1MTMtNDYzZi1hNTJkLTgzZmZmNDhmMDVjYSIsIndyaXRlS2V5IjoicldBZlZTSFJyY3Z4RzBVSDR2djNhRloyZElQbXYwOGMiLCJ1c2VySWQiOm51bGwsInNlbnRBdCI6IjIwMjQtMDYtMDVUMDU6MTU6MTguMTE0WiIsIl9tZXRhZGF0YSI6eyJidW5kbGVkIjpbIlNlZ21lbnQuaW8iXSwidW5idW5kbGVkIjpbXSwiYnVuZGxlZElkcyI6W119fX19LHsic2NoZW1hIjoiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvd2ViX3BhZ2UvanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsiaWQiOiJiNzE3MGI4OS05MWU3LTQ4NWUtYTdhNS1iODdjZDI0YzNlNDQifX1dfQ","stm":"1719229281944"}]}
            */
          println(decoded.body)
//          println(Base64.encodeBase64String(e))
        }
      case "err" =>
        events.foreach { e =>
          Console.err.println(Base64.encodeBase64String(e))
        }
    }

  override def shutdown(): Unit = ()
}
