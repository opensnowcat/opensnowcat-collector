# Testing

- Build the jar: sbt stdout/assembly
- Run the jar: java -jar stdout/target/scala-2.12/opensnowcat-collector-stdout.jar
- Run ngrok: ngrok http http://localhost:8080
- Take the https url from ngrok and convert it to the target url, like (no http prefix): 8f7d-85-152-106-87.ngrok-free.app/v1
- Open the tester page: https://www.snowcatcloud.com/demos/segment/
- Fill the target url at the field `Type your OpenSnowcat Collector`
- Fill an email
- Click on `analytics.page()`
