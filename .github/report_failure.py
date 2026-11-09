import os
import json
import urllib.request

with open('build_output.log', encoding='utf-8', errors='replace') as f:
    log = f.read()
# Los errores del compilador ("e: archivo:línea mensaje") van PRIMERO: antes quedaban enterrados bajo la traza de
# Gradle y el issue solo mostraba "Compilation error".
errores = []
for linea in log.splitlines():
    if linea.startswith('e: ') or ' e: file' in linea or 'error:' in linea or 'Unresolved reference' in linea:
        if linea not in errores:
            errores.append(linea.strip())
resumen = "\n".join(errores[:60]) if errores else "(sin líneas e: en el log)"
log = log[-50000:]

body = "ERRORES DEL COMPILADOR:\n```\n" + resumen + "\n```\n\nCOLA DEL LOG:\n```\n" + log + "\n```"
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
