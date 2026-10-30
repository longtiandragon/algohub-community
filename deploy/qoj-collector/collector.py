"""Private QOJ read service. Reuses the server browser; never exports its cookies."""
import hmac
import json
import os
import re
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlsplit


class FetchError(Exception):
    def __init__(self, kind, stage=None):
        self.kind = kind
        self.stage = stage


def target(path):
    parsed = urlsplit(path)
    query = parse_qs(parsed.query, keep_blank_values=True)
    if parsed.path not in ('/profile', '/submissions') or set(query) - {'handle', 'page'}:
        raise FetchError('VALIDATION')
    if any(len(values) != 1 for values in query.values()):
        raise FetchError('VALIDATION')
    handle = query.get('handle', [''])[0]
    if not re.fullmatch(r'[A-Za-z0-9_]{1,64}', handle):
        raise FetchError('VALIDATION')
    if parsed.path == '/profile':
        if 'page' in query:
            raise FetchError('VALIDATION')
        return 'profile', handle, 1, 'https://qoj.ac/user/profile/' + handle
    number = query.get('page', ['1'])[0]
    if not re.fullmatch(r'[1-9][0-9]{0,3}', number) or int(number) > 5000:
        raise FetchError('VALIDATION')
    return 'submissions', handle, int(number), 'https://qoj.ac/submissions?' + urlencode(
        {'submitter': handle, 'page': number})


# Only allowlisted visible profile/list data leaves the worker. No full HTML,
# scripts, hidden forms, response headers, storage state or browser credentials.
EXTRACT = r"""({kind, raw}) => {
    const source = new DOMParser().parseFromString(raw, 'text/html');
    const text = source.body?.textContent || '';
    const title = source.title || '';
    if (source.querySelector('input[type=password]') || /login/i.test(title))
        return {error: 'SESSION_EXPIRED', stage: 'login-document'};
    if (/just a moment|attention required/i.test(title) || source.querySelector('#challenge-form'))
        return {error: 'ACCESS_RESTRICTED'};
    const hasLogout = [...source.querySelectorAll('a,button')].some(a => /^Logout$/i.test(a.textContent.trim()));
    if (!hasLogout) return {error: 'SESSION_EXPIRED', stage: 'missing-logout-control'};
    if (kind === 'profile') {
        const headings = [...source.querySelectorAll('h4.list-group-item-heading')];
        const motto = headings.find(h => h.textContent.trim() === 'Motto');
        const accepted = headings.find(h => /^Accepted problems\s*[:：]/.test(h.textContent.trim()));
        const count = accepted?.textContent.match(/[:：]\s*(\d+)\s+problems?/);
        const heading = source.querySelector('.uoj-username');
        if (!motto?.nextElementSibling || !count) return {error: 'PARSE_ERROR'};
        const ids = [...accepted.nextElementSibling.querySelectorAll('a[href]')].map(a =>
            a.getAttribute('href').match(/^\/problem\/(\d+)$/)?.[1]);
        if (ids.some(id => !id) || new Set(ids).size !== Number(count[1])) return {error: 'PARSE_ERROR'};
        return {title, motto: motto.nextElementSibling.textContent.trim(), total: Number(count[1]), problemIds: ids};
    }
    const required = ['ID', 'Problem', 'Submitter', 'Result', 'Submit time'];
    const table = [...source.querySelectorAll('table')].find(t => {
        const headers = [...t.querySelectorAll('th')].map(h => h.textContent.trim());
        return required.every(h => headers.includes(h));
    });
    if (!table) return {error: 'PARSE_ERROR'};
    const headers = [...table.querySelectorAll('th')].map(h => h.textContent.trim());
    const rows = [];
    let empty = false;
    for (const tr of table.querySelectorAll('tr')) {
        const cells = [...tr.querySelectorAll('td')];
        if (!cells.length) continue;
        if (cells.length === 1 && cells[0].hasAttribute('colspan') && /^None$/i.test(cells[0].textContent.trim())) {
            empty = true; continue;
        }
        if (cells.length !== headers.length) return {error: 'PARSE_ERROR'};
        const cell = name => cells[headers.indexOf(name)];
        const link = name => cell(name)?.querySelector('a[href]');
        const score = cell('Result').querySelector('.uoj-score');
        rows.push({id: link('ID')?.getAttribute('href'), problem: link('Problem')?.getAttribute('href'),
            title: link('Problem')?.textContent.trim(), submitter: cell('Submitter').querySelector('.uoj-username')?.textContent.trim(),
            result: cell('Result').textContent.trim(), score: score?.getAttribute('data-score'), full: score?.getAttribute('data-full'),
            time: cell('Submit time').textContent.trim(), language: cell('Language')?.textContent.trim()});
    }
    const pagination = [...source.querySelectorAll('.pagination a[href]')].map(a => a.getAttribute('href'));
    const current = source.querySelector('.pagination .active')?.textContent.trim();
    return {title, headers, rows, empty, pagination, current, sourceClock: text.match(/Server Time:\s*[^\n]*?\(UTC[^)]*\)/)?.[0]};
}"""


class Collector:
    def __init__(self, playwright, interval=3.0):
        self.playwright = playwright
        self.interval = max(2.0, interval)
        self.browser = None
        self.page = None
        self.last = 0.0
        self.blocked_until = 0.0
        self.failure = None

    def fetch(self, path):
        kind, handle, number, url = target(path)
        if time.monotonic() < self.blocked_until:
            raise FetchError(self.failure)
        time.sleep(max(0, self.last + self.interval - time.monotonic()))
        try:
            if self.browser is None or not self.browser.is_connected():
                self.browser = self.playwright.chromium.connect_over_cdp('http://127.0.0.1:9223', timeout=10000)
                self.page = self.browser.contexts[0].new_page()
            if self.page is None or self.page.is_closed():
                self.page = self.browser.contexts[0].new_page()
            response = self.page.goto(url, wait_until='domcontentloaded', timeout=30000)
            actual = urlsplit(self.page.url)
            if actual.scheme != 'https' or actual.netloc != 'qoj.ac':
                raise FetchError('ACCESS_RESTRICTED')
            if actual.path == '/login':
                raise FetchError('SESSION_EXPIRED', 'redirect-to-login')
            status = response.status if response else 0
            if status == 429:
                raise FetchError('RATE_LIMITED')
            if status in (401, 403):
                raise FetchError('ACCESS_RESTRICTED')
            if status == 404:
                raise FetchError('NOT_FOUND')
            if status >= 500:
                raise FetchError('UPSTREAM_ERROR')
            if status != 200:
                raise FetchError('PARSE_ERROR')
            raw = response.text()
            if not raw or len(raw) > 2_000_000:
                raise FetchError('PARSE_ERROR')
            result = self.page.evaluate(EXTRACT, {'kind': kind, 'raw': raw})
            if result.get('error'):
                raise FetchError(result['error'], result.get('stage'))
            if kind == 'profile' and not re.match(re.escape(handle) + r'\s*-\s*User profile', result['title']):
                raise FetchError('PARSE_ERROR')
            if kind == 'submissions' and any(r.get('submitter') != handle for r in result['rows']):
                raise FetchError('PARSE_ERROR')
            return {'kind': kind, 'handle': handle, 'page': number, 'data': result}
        except FetchError as error:
            self.failure = error.kind
            if error.kind in ('SESSION_EXPIRED', 'ACCESS_RESTRICTED', 'RATE_LIMITED'):
                self.blocked_until = time.monotonic() + 300
            raise
        except Exception:
            # Never log Playwright exceptions: they can include page content or URLs.
            self.failure = 'NETWORK'
            self.blocked_until = time.monotonic() + 30
            raise FetchError('NETWORK') from None
        finally:
            self.last = time.monotonic()


def serve(collector, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            self.connection.settimeout(5)
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                self.send_error(403)
                return
            try:
                result = collector.fetch(self.path)
                status = 200
            except FetchError as error:
                result = {'error': error.kind, 'stage': error.stage}
                status = 400 if error.kind == 'VALIDATION' else 503
            encoded = json.dumps(result, ensure_ascii=False).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(encoded)))
            self.end_headers()
            try:
                self.wfile.write(encoded)
            except (BrokenPipeError, ConnectionResetError):
                pass

    # A single browser-owning thread serializes all requests. No per-user login.
    class Server(HTTPServer):
        request_queue_size = 4
    Server(('127.0.0.1', 9224), Handler).serve_forever()


if __name__ == '__main__':
    from playwright.sync_api import sync_playwright
    secret = Path(os.environ['QOJ_COLLECTOR_TOKEN_FILE']).read_text().strip()
    if len(secret) < 32:
        raise SystemExit('Collector token is not configured')
    with sync_playwright() as runtime:
        serve(Collector(runtime, float(os.getenv('QOJ_REQUEST_INTERVAL', '3'))), secret)
