/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import QRCode from 'qrcode';

export async function weixinQrImage(content: string): Promise<string> {
  // iLink normally returns the URL that must be encoded in the QR, not an
  // image URL. Render it locally instead of requesting it as a remote image.
  if (/^data:image\/(png|jpeg|gif|webp);base64,[a-z0-9+/=\s]+$/i.test(content)) return content;
  return QRCode.toDataURL(content, { width: 264, margin: 2, errorCorrectionLevel: 'M', color: { dark: '#142b24', light: '#ffffff' } });
}
