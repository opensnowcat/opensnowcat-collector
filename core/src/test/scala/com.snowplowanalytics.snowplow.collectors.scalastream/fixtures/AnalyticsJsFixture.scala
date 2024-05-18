package com.snowplowanalytics.snowplow.collectors.scalastream.fixtures

import io.circe.Json

object AnalyticsJsFixture {
  // These payloads can be generated at https://segment.com/docs/connections/spec/
  val pagePayload = unsafeJson(
    """{"timestamp":"2024-04-14T20:36:53.131Z","integrations":{},"userId":"562927","anonymousId":"e09fac9f-16e4-43f1-8753-c55023820f81","type":"page","properties":{"path":"/","referrer":"","search":"","title":"SnowcatCloud: Cloud-Hosted Snowplow SOC2 Type 2 Certified","url":"https://www.snowcatcloud.com"},"context":{"page":{"path":"/","referrer":"","search":"","title":"SnowcatCloud: Cloud-Hosted Snowplow SOC2 Type 2 Certified","url":"https://www.snowcatcloud.com"},"userAgent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36","userAgentData":{"brands":[{"brand":"Google Chrome","version":"123"},{"brand":"Not:A-Brand","version":"8"},{"brand":"Chromium","version":"123"}],"mobile":false,"platform":"macOS"},"locale":"en-US","library":{"name":"analytics.js","version":"next-1.64.0"},"timezone":"Europe/Madrid"},"messageId":"ajs-next-eae00995851506a0b58be4e786deeec9","writeKey":"rWAfVSHRrcvxG0UH4vv3aFZ2dIPmv08c","sentAt":"2024-04-14T20:36:53.134Z","_metadata":{"bundled":["Segment.io"],"unbundled":[],"bundledIds":[]}}"""
  )

  val identifyPayload = unsafeJson(
    """{"timestamp":"2024-05-17T20:30:07.948Z","integrations":{},"userId":"668679","anonymousId":"c2c2b51e-7a1c-46b9-aba4-d90260e864b6","type":"identify","traits":{"email":"test@sample.com"},"context":{"page":{"path":"/","referrer":"","search":"","title":"SnowcatCloud: Cloud-Hosted Snowplow SOC2 Type 2 Certified","url":"https://www.snowcatcloud.com"},"userAgent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36","userAgentData":{"brands":[{"brand":"Chromium","version":"124"},{"brand":"Google Chrome","version":"124"},{"brand":"Not-A.Brand","version":"99"}],"mobile":false,"platform":"macOS"},"locale":"en-US","library":{"name":"analytics.js","version":"next-1.64.0"},"timezone":"Europe/Madrid"},"messageId":"ajs-next-7d2b6449681170733bf2026b2e0d938b","writeKey":"rWAfVSHRrcvxG0UH4vv3aFZ2dIPmv08c","sentAt":"2024-05-17T20:30:07.955Z","_metadata":{"bundled":["Segment.io"],"unbundled":[],"bundledIds":[]}}"""
  )

  val trackPayload = unsafeJson(
    """{"timestamp":"2024-05-17T20:30:55.698Z","integrations":{},"userId":"668679","anonymousId":"c2c2b51e-7a1c-46b9-aba4-d90260e864b6","event":"Article Bookmarked","type":"track","properties":{"title":"Snow Fall","subtitle":"The Avalanche at Tunnel Creek","author":"John Branch"},"context":{"page":{"path":"/","referrer":"","search":"","title":"SnowcatCloud: Cloud-Hosted Snowplow SOC2 Type 2 Certified","url":"https://www.snowcatcloud.com"},"userAgent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36","userAgentData":{"brands":[{"brand":"Chromium","version":"124"},{"brand":"Google Chrome","version":"124"},{"brand":"Not-A.Brand","version":"99"}],"mobile":false,"platform":"macOS"},"locale":"en-US","library":{"name":"analytics.js","version":"next-1.64.0"},"timezone":"Europe/Madrid"},"messageId":"ajs-next-191a812b74780fcf29ce722aa22818f9","writeKey":"rWAfVSHRrcvxG0UH4vv3aFZ2dIPmv08c","sentAt":"2024-05-17T20:30:55.701Z","_metadata":{"bundled":["Segment.io"],"unbundled":[],"bundledIds":[]}}"""
  )

  val groupPayload = unsafeJson(
    """{"timestamp":"2024-05-17T20:34:21.521Z","integrations":{},"userId":"668679","anonymousId":"c2c2b51e-7a1c-46b9-aba4-d90260e864b6","type":"group","traits":{"principles":["Eckert","Mauchly"],"site":"Eckert–Mauchly Computer Corporation","statedGoals":"Develop the first commercial computer","industry":"Technology"},"groupId":"UNIVAC Working Group","context":{"page":{"path":"/","referrer":"","search":"","title":"SnowcatCloud: Cloud-Hosted Snowplow SOC2 Type 2 Certified","url":"https://www.snowcatcloud.com"},"userAgent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36","userAgentData":{"brands":[{"brand":"Chromium","version":"124"},{"brand":"Google Chrome","version":"124"},{"brand":"Not-A.Brand","version":"99"}],"mobile":false,"platform":"macOS"},"locale":"en-US","library":{"name":"analytics.js","version":"next-1.64.0"},"timezone":"Europe/Madrid"},"messageId":"ajs-next-19a5381df8b4061764f6e40409ed6882","writeKey":"rWAfVSHRrcvxG0UH4vv3aFZ2dIPmv08c","sentAt":"2024-05-17T20:34:21.525Z","_metadata":{"bundled":["Segment.io"],"unbundled":[],"bundledIds":[]}}"""
  )

  val aliasPayload = unsafeJson(
    """{"timestamp":"2024-05-17T20:38:43.526Z","integrations":{},"userId":"newid","anonymousId":"c2c2b51e-7a1c-46b9-aba4-d90260e864b6","type":"alias","previousId":"668679","context":{"page":{"path":"/","referrer":"","search":"","title":"SnowcatCloud: Cloud-Hosted Snowplow SOC2 Type 2 Certified","url":"https://www.snowcatcloud.com"},"userAgent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36","userAgentData":{"brands":[{"brand":"Chromium","version":"124"},{"brand":"Google Chrome","version":"124"},{"brand":"Not-A.Brand","version":"99"}],"mobile":false,"platform":"macOS"},"locale":"en-US","library":{"name":"analytics.js","version":"next-1.64.0"},"timezone":"Europe/Madrid"},"messageId":"ajs-next-718e81c0d211539377ce9f8f31b33e67","writeKey":"rWAfVSHRrcvxG0UH4vv3aFZ2dIPmv08c","sentAt":"2024-05-17T20:38:43.530Z","_metadata":{"bundled":["Segment.io"],"unbundled":[],"bundledIds":[]}}"""
  )

  val screenPayload = unsafeJson(
    """{"anonymousId":"3a12eab0-bca7-11e4-8dfc-aa07a5b093db","channel":"mobile","context":{"ip":"8.8.8.8"},"integrations":{"All":true,"Mixpanel":false,"Salesforce":false},"messageId":"022bb90c-bbac-11e4-8dfc-aa07a5b093db","name":"Dropdown selected","properties":{"variation":"blue"},"receivedAt":"2024-05-17T20:52:05.029Z","sentAt":"2024-05-17T20:52:04.796Z","timestamp":"2024-05-17T20:52:04.796Z","type":"screen","userId":"97980cfea0067","version":"1.1"}"""
  )

  private def unsafeJson(str: String): Json = io.circe.parser.parse(str).right.get
}
