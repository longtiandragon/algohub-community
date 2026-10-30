import unittest
from unittest.mock import Mock, patch
from collector import Collector, FetchError, target


class CollectorTest(unittest.TestCase):
    def test_target_cannot_escape_qoj_or_override_identity(self):
        for path in ['/profile?handle=../login', '/profile?handle=a&handle=b',
                     '/submissions?handle=a&page=0', '/submissions?handle=a&page=5001',
                     '/submissions?handle=a&url=https://other.invalid', '/login?handle=a',
                     '/profile?handle=a&page=2', '/submissions?handle=a%26page=2']:
            with self.subTest(path=path), self.assertRaises(FetchError):
                target(path)
        self.assertEqual(target('/submissions?handle=sample_user&page=6')[3],
                         'https://qoj.ac/submissions?submitter=sample_user&page=6')

    def collector(self, status=200, data=None):
        runtime = Mock()
        browser = runtime.chromium.connect_over_cdp.return_value
        context = Mock()
        browser.contexts = [context]
        page = context.new_page.return_value
        page.is_closed.return_value = False
        page.url = 'https://qoj.ac/submissions?submitter=sample_user&page=1'
        response = page.goto.return_value
        response.status = status
        response.text.return_value = '<html>not logged or returned to clients</html>'
        page.evaluate.return_value = data or {'rows': [], 'empty': True, 'current': None}
        return Collector(runtime), runtime, page

    @patch('collector.time.sleep')
    def test_one_browser_session_reused(self, sleep):
        worker, runtime, page = self.collector()
        worker.fetch('/submissions?handle=sample_user')
        worker.fetch('/submissions?handle=sample_user&page=2')
        runtime.chromium.connect_over_cdp.assert_called_once()
        self.assertEqual(page.goto.call_count, 2)

    @patch('collector.time.sleep')
    def test_access_failures_are_classified_and_circuit_breaks(self, sleep):
        for status, kind in [(403, 'ACCESS_RESTRICTED'), (429, 'RATE_LIMITED')]:
            worker, _, page = self.collector(status)
            for _ in range(2):
                with self.assertRaises(FetchError) as caught:
                    worker.fetch('/submissions?handle=sample_user')
                self.assertEqual(caught.exception.kind, kind)
            self.assertEqual(page.goto.call_count, 1)

    @patch('collector.time.sleep')
    def test_expired_session_not_reported_as_empty_history(self, sleep):
        worker, _, page = self.collector()
        page.url = 'https://qoj.ac/login'
        with self.assertRaises(FetchError) as caught:
            worker.fetch('/submissions?handle=sample_user')
        self.assertEqual(caught.exception.kind, 'SESSION_EXPIRED')

    @patch('collector.time.sleep')
    def test_empty_response_and_mismatched_submitter_rejected(self, sleep):
        worker, _, page = self.collector()
        page.goto.return_value.text.return_value = ''
        with self.assertRaises(FetchError) as caught:
            worker.fetch('/submissions?handle=sample_user')
        self.assertEqual(caught.exception.kind, 'PARSE_ERROR')
        worker, _, _ = self.collector(data={'rows': [{'submitter': 'other_user'}]})
        with self.assertRaises(FetchError) as caught:
            worker.fetch('/submissions?handle=sample_user')
        self.assertEqual(caught.exception.kind, 'PARSE_ERROR')

    @patch('collector.time.sleep')
    def test_network_exceptions_do_not_leak_source_content(self, sleep):
        worker, _, page = self.collector()
        page.goto.side_effect = RuntimeError('sensitive source text')
        with self.assertRaises(FetchError) as caught:
            worker.fetch('/submissions?handle=sample_user')
        self.assertEqual(caught.exception.kind, 'NETWORK')
        self.assertNotIn('sensitive', str(caught.exception))


if __name__ == '__main__':
    unittest.main()
