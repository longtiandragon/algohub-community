"""Export passed problems from a saved Luogu practice HTML, without network access."""
import argparse
import csv
import json
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import quote


class ContextParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.active = False
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag == 'script' and dict(attrs).get('id') == 'lentille-context':
            self.active = True

    def handle_endtag(self, tag):
        if tag == 'script':
            self.active = False

    def handle_data(self, data):
        if self.active:
            self.parts.append(data)


def extract(source):
    parser = ContextParser()
    parser.feed(source)
    context = json.loads(''.join(parser.parts))
    data = context['data']
    passed = data['passed']
    if not isinstance(passed, list):
        raise ValueError('data.passed must be an array')
    result = {}
    for problem in passed:
        pid = problem['pid']
        if not isinstance(pid, str) or not pid.strip():
            raise ValueError('Invalid problem ID')
        result[pid] = {
            'pid': pid, 'title': problem['name'],
            'url': 'https://www.luogu.com.cn/problem/' + quote(pid, safe=''),
            'difficulty': problem.get('difficulty'),
            'type': problem.get('type'),
            'tags': None, 'acceptedAt': None,
        }
    return list(result.values()), {
        'source': 'SAVED_LUOGU_PRACTICE_HTML',
        'uid': data.get('user', {}).get('uid'),
        'passedCount': len(result),
        'reportedPassedCount': data.get('user', {}).get('passedProblemCount'),
        'excludedSubmittedCount': len(data.get('submitted', [])),
        'difficultyCounts': dict(sorted(Counter(p['difficulty'] for p in result.values()).items(), key=lambda x: str(x[0]))),
        'tagsAvailable': False, 'acceptedTimesAvailable': False,
    }


def csv_safe(value):
    text = '' if value is None else str(value)
    return "'" + text if text.lstrip().startswith(('=', '+', '-', '@')) else text


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('html', type=Path)
    cli.add_argument('--output', type=Path, required=True)
    args = cli.parse_args()
    rows, metadata = extract(args.html.read_text(encoding='utf-8-sig'))
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / 'luogu-passed.csv').open('w', encoding='utf-8-sig', newline='') as file:
        writer = csv.writer(file)
        writer.writerow(['pid', 'title', 'url', 'difficulty', 'type', 'tags', 'acceptedAt'])
        writer.writerows([csv_safe(row[key]) for key in ['pid', 'title', 'url', 'difficulty', 'type', 'tags', 'acceptedAt']] for row in rows)
    (args.output / 'luogu-passed.json').write_text(json.dumps({'metadata': metadata, 'problems': rows}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(metadata, ensure_ascii=True))


if __name__ == '__main__':
    main()
