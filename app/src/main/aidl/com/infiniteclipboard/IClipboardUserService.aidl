// 文件: app/src/main/aidl/com/infiniteclipboard/IClipboardUserService.aidl
package com.infiniteclipboard;

interface IClipboardUserService {
    String getClipboardText();
    void destroy();
}