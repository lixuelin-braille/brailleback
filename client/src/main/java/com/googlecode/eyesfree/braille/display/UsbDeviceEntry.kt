/*
 * Copyright (C) 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.braille.display

/**
 * A brltty usb device entry (driver code + vendor/product id), from the native driver table.
 *
 * Constructed from native code (BrlttyWrapper.c) via JNI:
 * `<init>(Ljava/lang/String;II)V`, parameter order `driver, vendorId, productId`.
 * Keep the data class primary-constructor signature compatible with that.
 */
data class UsbDeviceEntry(
    /** The driver of the USB device. */
    val driver: String,
    /** The vendor id of the USB device. */
    val vendorId: Int,
    /** The product id of the USB device. */
    val productId: Int
)
