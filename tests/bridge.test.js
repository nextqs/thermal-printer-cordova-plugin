const assert = require('node:assert/strict');
const { test } = require('node:test');
const plugin = require('../www/thermal-printer');

test('USB status forwards selectors and both callbacks unchanged', () => {
  const data = { type: 'usb', id: 123, vendorId: 1208, productId: 3623, serialNumber: 'test' };
  const success = () => {};
  const error = () => {};
  let calls = 0;
  global.cordova = { exec(...args) {
    calls++;
    assert.deepEqual(args, [success, error, 'ThermalPrinter', 'getPrinterStatus', [data]]);
    assert.equal(args[4][0], data);
  } };
  try {
    plugin.getPrinterStatus(data, success, error);
    assert.equal(calls, 1);
  } finally {
    delete global.cordova;
  }
});
