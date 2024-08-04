# Testing

- Build the jar: sbt stdout/assembly
- Run the jar: java -jar stdout/target/scala-2.12/opensnowcat-collector-stdout.jar
- With custom config: java -Dconfig.file=/Users/chef/projects/snowcatcloud/opensnowcat-collector/stdout/src/main/resources/application.conf -jar stdout/target/scala-2.12/opensnowcat-collector-stdout.jar
- Run ngrok: ngrok http http://localhost:8080
- Take the https url from ngrok and convert it to the target url, like (no http prefix): 96bb-83-53-22-237.ngrok-free.app/v1
- Open the tester page: https://www.snowcatcloud.com/demos/segment/
- Fill the target url at the field `Type your OpenSnowcat Collector`
- Fill an email
- Click on `analytics.page()`


Valid request body

curl 'https://33d2-85-152-106-87.ngrok-free.app/com.snowplowanalytics.snowplow/tp2' \
-H 'content-type: application/json; charset=UTF-8' \
--data-raw
```
{
  "schema":"iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4",
  "data":[
    {
      "e":"pv",
      "url":"https://33d2-85-152-106-87.ngrok-free.app/snowplow-segment-valid.html",
      "page":"Fingerprint Inspection",
      "eid":"9759ff32-45d3-49fa-bd9a-b0e9fdee85cb",
      "tv":"js-3.4.0",
      "tna":"sp",
      "aid":"yourappid",
      "p":"web",
      "cookie":"1",
      "cs":"windows-1252",
      "lang":"en-US",
      "res":"1512x982",
      "cd":"30",
      "tz":"Europe/Madrid",
      "dtm":"1719233527988",
      "vp":"1512x422",
      "ds":"1512x422",
      "vid":"2",
      "sid":"8e96d31e-6e29-44de-b9ba-bb2562e81f17",
      "duid":"c2e619fa-1462-47b3-b677-d6b27abd42f5",
      "cx":"eyJzY2hlbWEiOiJpZ2x1OmNvbS5zbm93cGxvd2FuYWx5dGljcy5zbm93cGxvdy9jb250ZXh0cy9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6W3sic2NoZW1hIjoiaWdsdTpjb20uc2VnbWVudC9hbmFseXRpY3Nqcy9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6eyJwYXlsb2FkIjp7InRpbWVzdGFtcCI6IjIwMjQtMDYtMDVUMDU6MTU6MTguMTA5WiIsImludGVncmF0aW9ucyI6e30sInR5cGUiOiJwYWdlIiwicHJvcGVydGllcyI6eyJwYXRoIjoiL3NlZ21lbnQuaHRtbCIsInJlZmVycmVyIjoiIiwic2VhcmNoIjoiIiwidGl0bGUiOiJGaW5nZXJwcmludCBJbnNwZWN0aW9uIiwidXJsIjoiaHR0cHM6Ly9wcmV2aWV3LnNub3djYXRjbG91ZC5jb20vc2VnbWVudC5odG1sIn0sImNvbnRleHQiOnsicGFnZSI6eyJwYXRoIjoiL3NlZ21lbnQuaHRtbCIsInJlZmVycmVyIjoiIiwic2VhcmNoIjoiIiwidGl0bGUiOiJGaW5nZXJwcmludCBJbnNwZWN0aW9uIiwidXJsIjoiaHR0cHM6Ly9wcmV2aWV3LnNub3djYXRjbG91ZC5jb20vc2VnbWVudC5odG1sIn0sInVzZXJBZ2VudCI6Ik1vemlsbGEvNS4wIChNYWNpbnRvc2g7IEludGVsIE1hYyBPUyBYIDEwXzE1XzcpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIENocm9tZS8xMjUuMC4wLjAgU2FmYXJpLzUzNy4zNiIsInVzZXJBZ2VudERhdGEiOnsiYnJhbmRzIjpbeyJicmFuZCI6Ikdvb2dsZSBDaHJvbWUiLCJ2ZXJzaW9uIjoiMTI1In0seyJicmFuZCI6IkNocm9taXVtIiwidmVyc2lvbiI6IjEyNSJ9LHsiYnJhbmQiOiJOb3QuQS9CcmFuZCIsInZlcnNpb24iOiIyNCJ9XSwibW9iaWxlIjpmYWxzZSwicGxhdGZvcm0iOiJtYWNPUyJ9LCJsb2NhbGUiOiJlbi1VUyIsImxpYnJhcnkiOnsibmFtZSI6ImFuYWx5dGljcy5qcyIsInZlcnNpb24iOiJuZXh0LTEuNzAuMCJ9LCJ0aW1lem9uZSI6IkFtZXJpY2EvTG9zX0FuZ2VsZXMifSwibWVzc2FnZUlkIjoiYWpzLW5leHQtMTcxNzU2NDUxODEwOS03MWNkOGRjNS00ZTM1LTQ1MjAtODgxOC03ODRmZDU0YmJlODQiLCJhbm9ueW1vdXNJZCI6IjUwZGQ3ZmZmLWE1MTMtNDYzZi1hNTJkLTgzZmZmNDhmMDVjYSIsIndyaXRlS2V5IjoicldBZlZTSFJyY3Z4RzBVSDR2djNhRloyZElQbXYwOGMiLCJ1c2VySWQiOm51bGwsInNlbnRBdCI6IjIwMjQtMDYtMDVUMDU6MTU6MTguMTE0WiIsIl9tZXRhZGF0YSI6eyJidW5kbGVkIjpbIlNlZ21lbnQuaW8iXSwidW5idW5kbGVkIjpbXSwiYnVuZGxlZElkcyI6W119fX19LHsic2NoZW1hIjoiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvd2ViX3BhZ2UvanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsiaWQiOiJkYmFjYjU1MC05MWMzLTRjOTAtOWQ5ZS02MGY2NGFkNWEwYjMifX1dfQ",
      "stm":"1719233527990"
    }
  ]
}
```


Valid event from the js sdk, this is printed from enricher:

```json

```