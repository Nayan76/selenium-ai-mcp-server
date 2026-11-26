export const browser = origin => [
  ['launch_browser', {browser: 'chrome'}],
  ['navigate', {url: origin + '/'}],
  ['find_elements', {selector: '#ready'}],
  ['assert_element_text', {selector: '#ready', expected: 'Fixture ready'}],
  ['take_screenshot', {}],
  ['close_browser', {}],
];
export const send = origin => ({method: 'GET', url: origin + '/api/health'});
export const assertions = requestId => [
  ['assert_api_status', {requestId, expectedStatus: 200}],
  ['assert_api_response', {requestId, jsonPath: '$.ready', assertionType: 'equals', expected: true}],
];
export const collection = origin => ({
  collectionName: 'Fixture read chain',
  requests: [
    {id: 'first', name: 'Read ID', method: 'GET', url: origin + '/api/first',
      assertions: [{type: 'status', expectedStatus: 200}], extract: {itemId: '$.id'}},
    {id: 'second', name: 'Check item', method: 'GET', url: origin + '/api/items/{{itemId}}',
      assertions: [{type: 'json', jsonPath: '$.name', assertionType: 'equals', expected: 'Fixture item'}]},
  ],
});
export const profiles = origin => ({
  schemaVersion: 1,
  profiles: {dev: {enabled: true, origin, routes: {health: {method: 'GET', path: '/api/health'}}}},
});
export const saved = {
  name: 'Saved health check',
  requests: [{id: 'health', name: 'Check health', route: 'health', expectedStatus: 200}],
};
