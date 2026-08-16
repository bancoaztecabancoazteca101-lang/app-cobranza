import os
import json
import urllib.request

with open('build_output.log', encoding='utf-8', errors='replace') as f:
    log = f.read()
log = log[-60000:]

body = "```\n" + log + "\n```"
sha = os.environ.get('GITHUB_SHA', '')[:7]
repo = os.environ['GH_REPO']
token = os.environ['GH_TOKEN']

data = json.dumps({
    'title': f'Build failed - {sha}',
    'body': body
}).encode('utf-8')

req = urllib.request.Request(
    f'https://api.github.com/repos/{repo}/issues',
    data=data,
    headers={
        'Authorization': f'token {token}',
        'Accept': 'application/vnd.github+json',
        'Content-Type': 'application/json'
    }
)
urllib.request.urlopen(req)
print("Issue created")
