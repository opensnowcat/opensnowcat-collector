#!/usr/bin/python3
import requests
import unittest


class TestTelemetry(unittest.TestCase):
    all = requests.request("GET", "http://127.0.0.1:9191/micro/all").json()
    # {
    #   "total": 55,
    #   "good": 55,
    #   "bad": 0
    # }
    good = requests.request("GET", "http://127.0.0.1:9191/micro/good").json()
    #  [ ...{
    # "unstruct_event": {
    #     "schema": "iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
    #     "data":
    #         "schema": "iglu:com.snowplowanalytics.oss/oss_context/jsonschema/1-0-1",
    #         "data": {
    #             "userProvidedId": "userProvidedIdValue",
    #             "moduleName": "moduleNameValue",
    #             "moduleVersion": null,
    #             "instanceId": null,
    #             "region": null,
    #             "cloud": null,
    #             "applicationName": "opensnowcat-collector-stdout",
    #             "applicationVersion": "2.3.1",
    #             "appGeneratedId": "00968dc0-26de-4378-abcf-00329c8020b6"
    #         }
    #     }
    # }
    event_data = [entry['event']['unstruct_event']['data'] for entry in good]

    def test_no_bad_events(self):
        self.assertEqual(self.all["bad"], 0)

    def test_frequency(self):
        # should be around 30 - 1 per second over 30 seconds
        self.assertGreater(self.all["good"], 20)

    def test_consistency(self):
        # Test that we got the same events. Comparing first to last.
        self.assertDictEqual(self.event_data[0], self.event_data[-1])

    def test_no_events_when_disabled(self):
        # disabled collector should not send any events.
        self.assertFalse(any(e['data']['moduleName'] == 'Disabled' for e in self.event_data))

    def test_collector_name_is_taken_from_build(self):
        # Version correctly taken from BuildInfo
        self.assertTrue(
            all(e['data']['applicationName'] == "opensnowcat-collector-stdout" for e in self.event_data))


if __name__ == '__main__':
    unittest.main()
