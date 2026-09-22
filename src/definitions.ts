export interface Printer {
    // Bluetooth
    address?: string;
    bondState?: number;
    name?: string;
    type?: number;
    features?: string[];

    // USB
    productName?: string;
    manufacturerName?: string;
    deviceId?: number;
    serialNumber?: string;
    vendorId?: number;
    productId?: number;

    // Internal Android printer (Gertec GPOS820 / Urovo-compatible runtime)
    id?: string;
    manufacturer?: string;
    brand?: string;
    model?: string;
    device?: string;
}

export interface PrinterToUse {
    type: 'bluetooth' | 'tcp' | 'usb' | 'internal-urovo';
    id: string | number;
    address?: string;
    port?: number;
    /** Stable USB identifiers used after the device re-enumerates. */
    vendorId?: number;
    productId?: number;
    serialNumber?: string;
}

export interface PrintFormattedText extends PrinterToUse {
    text: string;
    mmFeedPaper?: number;
    dotsFeedPaper?: number;
    printerDpi?: number;
    printerWidthMM?: number;
    printerNbrCharactersPerLine?: number;
    printerModel?: string;
    charsetEncoding?: {
        charsetName: string,
        charsetId: number
    };
}

export interface BitmapToHexadecimalString extends PrinterToUse {
    base64: string;
}

// ─── Urovo internal printer structured operations ───────────────────────────

export type UrovoTextSize = 'small' | 'normal' | 'title' | 'ticket';
export type UrovoAlign = 'left' | 'center' | 'right';

export interface UrovoTextOperation {
    kind: 'text';
    text: string;
    align: UrovoAlign;
    size: UrovoTextSize;
    bold?: boolean;
    /** Extra vertical gap in dots after this element */
    gap?: number;
}

export interface UrovoQrOperation {
    kind: 'qr';
    value: string;
    align: UrovoAlign;
    /** QR module size in dots (240–320, default 280) */
    size?: number;
    /** Optional absolute X position in dots for devices that need precise QR centering */
    x?: number;
    /** Optional vertical space in dots before drawing the QR */
    topGap?: number;
    /** Extra vertical gap in dots after this element */
    gap?: number;
}

export interface UrovoImageOperation {
    kind: 'image';
    /** Base64-encoded image (PNG/JPEG, with or without data URI prefix) */
    base64: string;
    align: UrovoAlign;
    /** Extra vertical gap in dots after this element */
    gap?: number;
}

export interface UrovoGapOperation {
    kind: 'gap';
    /** Vertical space in dots */
    dots: number;
}

export type UrovoOperation =
    | UrovoTextOperation
    | UrovoQrOperation
    | UrovoImageOperation
    | UrovoGapOperation;

export interface PrintInternalUrovoPage {
    type: 'internal-urovo';
    id: 'internal-urovo';
    operations: UrovoOperation[];
}

export interface RequestPermissionsResult {
    granted: boolean;
}

export interface GetEncodingResult {
    name: string;
    command?: string[];
}

export interface ErrorResult {
    error?: string;
}

/**
 * Available since v1.2.0 — shapes returned by getUsbDiagnostics() and onUsbEvent().
 */

export interface UsbEndpointInfo {
    address: number;
    /** UsbConstants.USB_ENDPOINT_XFER_* */
    type: number;
    direction: 'in' | 'out';
    maxPacketSize: number;
}

export interface UsbInterfaceInfo {
    id: number;
    class: number;
    subclass: number;
    protocol: number;
    endpoints: UsbEndpointInfo[];
}

export interface UsbDeviceInfo {
    deviceName: string;
    /** Changes every time the device re-enumerates on the bus */
    deviceId: number;
    vendorId: number;
    productId: number;
    deviceClass: number;
    interfaceCount: number;
    interfaces: UsbInterfaceInfo[];
    productName?: string;
    manufacturerName?: string;
    /** Only reported by getUsbDiagnostics(), not by the event stream */
    hasPermission?: boolean;
    /** Only reported by getUsbDiagnostics(), and only when readable */
    serialNumber?: string;
}

export interface UsbPowerState {
    /** BatteryManager.EXTRA_PLUGGED: 0 when unplugged, -1 when unknown */
    plugged?: number;
    pluggedLabel?: 'ac' | 'usb' | 'wireless' | 'unplugged' | 'other';
    /** BatteryManager.EXTRA_STATUS */
    status?: number;
    levelPercent?: number;
    voltageMv?: number;
}

export interface UsbEvent {
    timestamp?: number;
    uptimeMs?: number;
    /** e.g. android.hardware.usb.action.USB_DEVICE_ATTACHED, android.intent.action.POWER_CONNECTED */
    action?: string;
    /** Present on attach/detach only */
    device?: UsbDeviceInfo;
    /** Sticky USB_STATE extras, stringified. Present on USB_STATE only */
    extras?: { [key: string]: string | null };
    power?: UsbPowerState;
    /** Number of devices reported by UsbManager, or -1 when unavailable */
    usbDeviceCount?: number;
}

export interface UsbConnectionCacheEntry {
    key: string;
    isConnected: boolean;
    deviceName?: string;
    deviceId?: number;
}

export interface UsbDiagnostics {
    timestamp?: number;
    uptimeMs?: number;
    /** Manufacturer, model, SDK level and build */
    device?: string;
    /** False when the USB/power receiver failed to register: the connection cache is then only cleared on detach and on print failure */
    receiverRegistered?: boolean;
    eventListenerAttached?: boolean;
    verboseLogging?: boolean;
    power?: UsbPowerState;
    usbState?: { [key: string]: string | null };
    usbDevices?: UsbDeviceInfo[];
    connectionCache?: UsbConnectionCacheEntry[];
    recentEvents?: UsbEvent[];
}

export interface GetPrinterStatus extends PrinterToUse {
    type: 'usb';
}

export type PrinterStatusReason = 'unsupported_transport' | 'busy' | 'device_not_found'
    | 'permission_required' | 'no_status_endpoint' | 'interface_unavailable'
    | 'input_not_quiet' | 'timeout' | 'write_timeout' | 'invalid_response' | 'io_error' | 'partial';

/** Experimental ESC/POS USB snapshot; null always means unknown, never a printer fault. */
export interface PrinterStatus {
    /** True when at least one valid DLE EOT reply was received; null when support is unknown. */
    supported: boolean | null;
    paperPresent: boolean | null;
    /** Requires a near-end sensor on the printer. */
    paperNearEnd: boolean | null;
    /** Requires the model to implement the standard offline-status cover bit. */
    coverOpen: boolean | null;
    /** USB printer class error bit: the printer stopped, without saying why. Blocks printing. */
    printerStopped: boolean | null;
    /** Raw unsigned bytes, including malformed replies; empty arrays mean no reply was read. */
    raw: { paper: number[]; offline: number[]; port: number[]; };
    reason: PrinterStatusReason | null;
}

export interface ThermalPrinterPlugin {
  /**
   * Query paper/cover sensors over USB. Available since v1.2.0; validate each model on the bench.
   * Busy, absent, unsupported and silent printers use the success callback with unknown fields.
   * Never block ticket issuance on null fields or supported !== true.
   */
  getPrinterStatus?(data: GetPrinterStatus, success: (value: PrinterStatus) => void, error: (value: ErrorResult) => void): void;

  /**
   * List available printers
   *
   * @param {Object} data - Data object
   * @param {"bluetooth"|"usb"|"internal-urovo"} data.type - Type of list: bluetooth, usb or internal-urovo
   * @param {function} success
   * @param {function} error
   */
  listPrinters(data: { type: 'bluetooth' | 'usb' | 'internal-urovo'; }, success: (value: Printer[]) => any, error: (value: ErrorResult) => void);

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
   * @param {function} success
   * @param {function} error
   */
  printFormattedText(data: PrintFormattedText, success: () => void, error: (value: ErrorResult) => void);

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
   * @param {function} success
   * @param {function} error
   */
  printFormattedTextAndCut(data: PrintFormattedText, success: () => void, error: (value: ErrorResult) => void);

  /**
   * Get the printer encoding when available
   *
   * @param {Object[]} data - Data object
   * @param {"bluetooth"|"tcp"|"usb"} data.type - List all bluetooth or usb printers
   * @param {string|number} [data.id] - ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId)
   * @param {string} [data.address] - If type is "tcp" then the IP Address of the printer
   * @param {number} [data.port] - If type is "tcp" then the Port of the printer
   * @param {function} success
   * @param {function} error
   */
  getEncoding(data: PrinterToUse, success: (value: GetEncodingResult) => any, error: (value: ErrorResult) => void);

  /**
   * Close the connection with the printer
   *
   * @param {Object[]} data - Data object
   * @param {"bluetooth"|"tcp"|"usb"} data.type - List all bluetooth or usb printers
   * @param {string|number} [data.id] - ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId)
   * @param {string} [data.address] - If type is "tcp" then the IP Address of the printer
   * @param {number} [data.port] - If type is "tcp" then the Port of the printer
   * @param {function} success
   * @param {function} error
   */
  disconnectPrinter(data: PrinterToUse, success: () => void, error: (value: ErrorResult) => void);

  /**
   * USB/power diagnostics: UsbManager devices, connection cache, USB_STATE, battery and recent events
   *
   * Available since v1.2.0. Optional so that code written against 1.1.0 keeps type-checking; guard with
   * `typeof ThermalPrinter.getUsbDiagnostics === 'function'` when the plugin version is not pinned.
   *
   * @param {function} success
   * @param {function} error
   */
  getUsbDiagnostics?(success: (value: UsbDiagnostics) => void, error: (value: ErrorResult) => void): void;

  /**
   * Stream USB attach/detach, USB_STATE, power and screen events. The success callback is called for every event.
   *
   * Available since v1.2.0. Optional for the same reason as getUsbDiagnostics. Calling it again replaces the
   * previous subscription, so subscribe once.
   *
   * @param {function} success
   * @param {function} error
   */
  onUsbEvent?(success: (event: UsbEvent) => void, error: (value: ErrorResult) => void): void;

  /**
   * Request permissions for USB printers
   *
   * @param {Object[]} data - Data object
   * @param {"bluetooth"|"tcp"|"usb"} data.type - List all bluetooth or usb printers
   * @param {string|number} [data.id] - ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId)
   * @param {string} [data.address] - If type is "tcp" then the IP Address of the printer
   * @param {number} [data.port] - If type is "tcp" then the Port of the printer
   * @param {function} success
   * @param {function} error
   */
  requestPermissions(data: PrinterToUse, success: (value: RequestPermissionsResult) => any, error: (value: ErrorResult) => void);

  /**
   * Convert Drawable instance to a hexadecimal string of the image data
   *
   * @param {Object[]} data - Data object
   * @param {"bluetooth"|"tcp"|"usb"} data.type - List all bluetooth or usb printers
   * @param {string|number} [data.id] - ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId)
   * @param {string} [data.address] - If type is "tcp" then the IP Address of the printer
   * @param {number} [data.port] - If type is "tcp" then the Port of the printer
   * @param {string} data.base64 - Base64 encoded picture string to convert
   * @param {function} success
   * @param {function} error
   */
  bitmapToHexadecimalString(data: BitmapToHexadecimalString, success: (value: string) => any, error: (value: ErrorResult) => void);

  /**
   * Print a page on the Urovo/Gertec internal printer using structured operations.
   * The plugin resolves layout coordinates; the caller only specifies content and style.
   *
   * @param {PrintInternalUrovoPage} data - Page descriptor with ordered operations array
   * @param {function} success
   * @param {function} error
   */
  printInternalUrovoPage(data: PrintInternalUrovoPage, success: () => void, error: (value: ErrorResult) => void);
}
