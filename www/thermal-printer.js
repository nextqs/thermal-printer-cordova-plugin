/* global cordova, module */

module.exports = {
  /**
   * List available printers
   *
   * @param {Object} data - Data object
   * @param {"bluetooth"|"usb"|"internal-urovo"} data.type - Type of list: bluetooth, usb or internal-urovo
   * @param {function} successCallback - Result on success
   * @param {function} errorCallback - Result on failure
   */
  listPrinters: function (data, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, 'ThermalPrinter', 'listPrinters', [data]);
  },

  /**
   * Print a formatted text and feed paper
   * @see https://github.com/DantSu/ESCPOS-ThermalPrinter-Android#formatted-text--syntax-guide
   *
   * @param {Object[]} data - Data object
   * @param {"bluetooth"|"tcp"|"usb"} data.type - List all bluetooth or usb printers
   * @param {string|number} [data.id] - ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId)
   * @param {string} [data.address] - If type is "tcp" then the IP Address of the printer
   * @param {number} [data.port] - If type is "tcp" then the Port of the printer
   * @param {number} [data.mmFeedPaper] - Millimeter distance feed paper at the end
   * @param {number} [data.dotsFeedPaper] - Distance feed paper at the end
   * @param {string} data.text - Formatted text to be printed
   * @param {function} successCallback - Result on success
   * @param {function} errorCallback - Result on failure
   */
  printFormattedText: function (data, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, 'ThermalPrinter', 'printFormattedText', [data]);
  },

  /**
   * Print a formatted text, feed paper and cut the paper
   * @see https://github.com/DantSu/ESCPOS-ThermalPrinter-Android#formatted-text--syntax-guide
   *
   * @param {Object[]} data - Data object
   * @param {"bluetooth"|"tcp"|"usb"} data.type - List all bluetooth or usb printers
   * @param {string|number} [data.id] - ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId)
   * @param {string} [data.address] - If type is "tcp" then the IP Address of the printer
   * @param {number} [data.port] - If type is "tcp" then the Port of the printer
   * @param {number} [data.mmFeedPaper] - Millimeter distance feed paper at the end
   * @param {number} [data.dotsFeedPaper] - Distance feed paper at the end
   * @param {string} data.text - Formatted text to be printed
   * @param {function} successCallback - Result on success
   * @param {function} errorCallback - Result on failure
   */
  printFormattedTextAndCut: function (data, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, 'ThermalPrinter', 'printFormattedTextAndCut', [data]);
  },

  /**
   * Get the printer encoding when available
   *
   * @param {Object[]} data - Data object
   * @param {"bluetooth"|"tcp"|"usb"} data.type - List all bluetooth or usb printers
   * @param {string|number} [data.id] - ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId)
   * @param {string} [data.address] - If type is "tcp" then the IP Address of the printer
   * @param {number} [data.port] - If type is "tcp" then the Port of the printer
   * @param {function} successCallback - Result on success
   * @param {function} errorCallback - Result on failure
   */
  getEncoding: function (data, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, 'ThermalPrinter', 'getEncoding', [data]);
  },

  /**
   * Close the connection with the printer
   *
   * @param {Object[]} data - Data object
   * @param {"bluetooth"|"tcp"|"usb"} data.type - List all bluetooth or usb printers
   * @param {string|number} [data.id] - ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId)
   * @param {string} [data.address] - If type is "tcp" then the IP Address of the printer
   * @param {number} [data.port] - If type is "tcp" then the Port of the printer
   * @param {function} successCallback - Result on success
   * @param {function} errorCallback - Result on failure
   */
  disconnectPrinter: function (data, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, 'ThermalPrinter', 'disconnectPrinter', [data]);
  },

  /**
   * Request permissions for USB printers
   *
   * @param {Object[]} data - Data object
   * @param {"bluetooth"|"tcp"|"usb"} data.type - List all bluetooth or usb printers
   * @param {string|number} [data.id] - ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId)
   * @param {string} [data.address] - If type is "tcp" then the IP Address of the printer
   * @param {number} [data.port] - If type is "tcp" then the Port of the printer
   * @param {function} successCallback - Result on success
   * @param {function} errorCallback - Result on failure
   */
  requestPermissions: function (data, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, 'ThermalPrinter', 'requestPermissions', [data]);
  },

  /**
   * Convert Drawable instance to a hexadecimal string of the image data
   *
   * @param {Object[]} data - Data object
   * @param {"bluetooth"|"tcp"|"usb"} data.type - List all bluetooth or usb printers
   * @param {string|number} [data.id] - ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId)
   * @param {string} [data.address] - If type is "tcp" then the IP Address of the printer
   * @param {number} [data.port] - If type is "tcp" then the Port of the printer
   * @param {string} data.base64 - Base64 encoded picture string to convert
   * @param {function} successCallback - Result on success
   * @param {function} errorCallback - Result on failure
   */
  bitmapToHexadecimalString: function (data, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, 'ThermalPrinter', 'bitmapToHexadecimalString', [data]);
  },

  /**
   * Print a page on the Urovo/Gertec internal printer using structured operations.
   * Layout coordinates are resolved by the plugin; caller specifies only content and style.
   *
   * @param {Object} data - Page descriptor
   * @param {'internal-urovo'} data.type - Must be 'internal-urovo'
   * @param {'internal-urovo'} data.id - Must be 'internal-urovo'
   * @param {Array} data.operations - Ordered array of UrovoOperation objects
   * @param {function} successCallback - Result on success
   * @param {function} errorCallback - Result on failure
   */
  printInternalUrovoPage: function (data, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, 'ThermalPrinter', 'printInternalUrovoPage', [data]);
  },
};
