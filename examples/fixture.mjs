import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

export function respond(request, response) {
  if (request.method !== 'GET') {
    response.writeHead(405).end();
    return;
  }
  if (request.url === '/') {
    response.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
    response.end('<!doctype html><title>Selenium fixture</title><h1 id="ready">Fixture ready</h1>');
    return;
  }
  const bodies = {'/api/health': {ready: true}, '/api/first': {id: 'fixture-1'},
    '/api/items/fixture-1': {name: 'Fixture item'}};
  if (!Object.hasOwn(bodies, request.url)) {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, {'Content-Type': 'application/json'});
  response.end(JSON.stringify(bodies[request.url]));
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  createServer(respond).listen(8080, '127.0.0.1', () => console.log('Fixture listening on http://127.0.0.1:8080'));
}
