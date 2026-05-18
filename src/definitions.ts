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

export interface ThermalPrinterPlugin {
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
