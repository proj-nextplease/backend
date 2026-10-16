/**
 * Đồng bộ danh sách chờ FONLIO về Google Sheet.
 *
 * CÁCH CÀI (làm một lần):
 *  1. Mở Google Sheet muốn chứa dữ liệu → Extensions → Apps Script.
 *  2. Dán toàn bộ file này vào, thay API_URL bên dưới bằng domain thật.
 *  3. Project Settings (bánh răng) → Script properties → Add script property:
 *       Property: WAITLIST_EXPORT_TOKEN
 *       Value:    <token đã đặt ở biến môi trường WAITLIST_EXPORT_TOKEN của backend>
 *     Để token ở ĐÂY chứ không viết thẳng vào code: người được chia sẻ Sheet
 *     ở quyền xem/sửa sẽ KHÔNG đọc được script properties, còn code thì đọc
 *     được. Cũng vì vậy đừng dùng công thức =IMPORTDATA() kèm token trên ô —
 *     ai mở sheet cũng copy được URL rồi tải trọn danh sách.
 *  4. Chạy hàm `syncWaitlist` một lần để Google xin quyền.
 *  5. Chạy hàm `taoLichChay` một lần để đặt lịch tự chạy (mặc định 5 phút/lần;
 *     đổi ở hằng số PHUT_MOI_LAN bên dưới rồi chạy lại hàm này).
 *
 * Script GHI ĐÈ cả sheet mỗi lần chạy. Nhờ vậy thao tác là bất biến: chạy lại
 * bao nhiêu lần cũng ra cùng kết quả, không sinh dòng trùng, và dòng nào sửa
 * dưới DB thì sheet cũng theo. Đừng gõ tay vào sheet này — lần đồng bộ sau sẽ
 * xoá mất; cần ghi chú thì thêm một sheet khác rồi tham chiếu sang.
 */

const API_URL = 'https://api.fonlio.vn/api/v1/waitlist/export'; // ← đổi thành domain backend thật
const SHEET_NAME = 'Waitlist';

/* Bao lâu kéo dữ liệu một lần (phút).
   Apps Script CHỈ nhận 1, 5, 10, 15 hoặc 30 — số khác thì `everyMinutes()`
   ném lỗi khó hiểu, nên hàm taoLichChay chặn sẵn ở dưới.
   Đổi số ở đây xong phải CHẠY LẠI `taoLichChay`, vì lịch cũ đã nằm bên Google
   rồi, sửa file không tự đổi được nó.

   Vì sao 1 phút cần cẩn thận: tài khoản Gmail thường chỉ được khoảng 90 phút
   TỔNG thời gian chạy script mỗi ngày. 1 phút/lần = 1440 lượt/ngày, nên mỗi
   lượt chỉ được phép tốn vài giây. Đó là lý do có đoạn "bỏ qua khi không có
   gì mới" bên dưới: lượt nào dữ liệu không đổi thì không ghi lại sheet, chỉ
   tốn đúng một lần gọi mạng. Vượt hạn mức thì Google ngừng chạy lịch, và
   triệu chứng y hệt cái bạn đang gặp: sheet đứng im. */
const PHUT_MOI_LAN = 1;

/* Thứ tự cột trên sheet. Thêm cột mới ở backend thì thêm một dòng vào đây. */
const COLUMNS = [
  ['created_at',     'Thời điểm đăng ký'],
  ['full_name',      'Họ và tên'],
  ['email',          'Email'],
  ['email_consent',  'Đồng ý nhận email'],
  ['career_stage',   'Giai đoạn'],
  ['specialization', 'Lĩnh vực'],
  ['project_count',  'Số dự án'],
  ['main_challenge', 'Khó khăn lớn nhất'],
  ['beta_interest',  'Mức độ muốn thử nghiệm'],
  ['source',         'Nguồn'],
];

function syncWaitlist() {
  const token = PropertiesService.getScriptProperties().getProperty('WAITLIST_EXPORT_TOKEN');
  if (!token) {
    throw new Error('Chưa đặt WAITLIST_EXPORT_TOKEN trong Script properties. Xem hướng dẫn ở đầu file.');
  }

  const res = UrlFetchApp.fetch(API_URL, {
    method: 'get',
    headers: { 'X-Export-Token': token },
    muteHttpExceptions: true, // tự đọc mã lỗi để báo cho rõ, thay vì ném lỗi trống
  });

  const code = res.getResponseCode();
  if (code === 404) {
    throw new Error('Backend trả 404. Hoặc token sai, hoặc WAITLIST_EXPORT_TOKEN chưa được đặt ở backend.');
  }
  if (code !== 200) {
    throw new Error('Backend trả ' + code + ': ' + res.getContentText().slice(0, 300));
  }

  const payload = JSON.parse(res.getContentText());
  if (!payload.success) {
    throw new Error(payload.message || 'Backend báo thất bại.');
  }

  const rows = payload.data || [];

  /* Bỏ qua khi không có gì mới.
     Ở nhịp 1 phút thì hầu hết các lượt chạy là vô ích — danh sách chờ không
     có người đăng ký mỗi phút. Ghi lại cả sheet và autoResizeColumns là hai
     việc tốn thời gian nhất ở đây, nên chỉ làm khi dữ liệu THẬT SỰ đổi.
     Dấu nhận biết gồm cả số dòng lẫn mốc thời gian mới nhất: chỉ đếm số dòng
     thì một người sửa lại đăng ký cũ sẽ bị bỏ sót. */
  const props = PropertiesService.getScriptProperties();
  const dauVet = rows.length + '|' + (rows.length ? JSON.stringify(rows[rows.length - 1]) : '');
  const sheet = laySheet();

  /* Kèm điều kiện sheet đã có dữ liệu: nếu ai lỡ xoá sạch tab thì dấu vết cũ
     vẫn khớp, và nếu không kiểm thì script sẽ bỏ qua mãi mãi, để tab trống. */
  if (props.getProperty('DAU_VET_LAN_TRUOC') === dauVet && sheet.getLastRow() > 0) {
    ghiMocDongBo(sheet, COLUMNS.length, rows.length, true);
    return;
  }

  const header = COLUMNS.map(function (c) { return c[1]; });
  const values = [header].concat(rows.map(function (row) {
    return COLUMNS.map(function (c) { return dinhDang(row[c[0]]); });
  }));

  sheet.clearContents();
  sheet.getRange(1, 1, values.length, header.length).setValues(values);
  sheet.setFrozenRows(1);
  sheet.getRange(1, 1, 1, header.length).setFontWeight('bold');
  sheet.autoResizeColumns(1, header.length); // chỉ chạy ở lượt có thay đổi

  props.setProperty('DAU_VET_LAN_TRUOC', dauVet);
  ghiMocDongBo(sheet, header.length, rows.length, false);
}

/**
 * Mốc "đồng bộ lúc…" cạnh bảng — cách duy nhất để biết lịch còn sống hay đã
 * chết im lặng. Google tắt trigger sau vài lần lỗi liên tiếp mà không báo gì
 * trên sheet, nên không có mốc này thì không phân biệt được "chưa ai đăng ký"
 * với "script đã ngừng chạy từ hôm qua".
 */
function ghiMocDongBo(sheet, soCot, soNguoi, khongDoi) {
  sheet.getRange(1, soCot + 2).setValue(
    'Đồng bộ lúc ' + Utilities.formatDate(new Date(), 'Asia/Ho_Chi_Minh', 'dd/MM/yyyy HH:mm:ss')
      + ' · ' + soNguoi + ' người' + (khongDoi ? ' (không có thay đổi)' : '')
  );
}

function laySheet() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  return ss.getSheetByName(SHEET_NAME) || ss.insertSheet(SHEET_NAME);
}

function dinhDang(value) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'boolean') return value ? 'Có' : 'Không';
  // created_at về dạng ISO. Đổi sang giờ Việt Nam cho người đọc.
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(value)) {
    return Utilities.formatDate(new Date(value), 'Asia/Ho_Chi_Minh', 'dd/MM/yyyy HH:mm');
  }
  return value;
}

/**
 * Đặt lịch chạy định kỳ.
 *
 * Gỡ lịch cũ trước rồi mới tạo lịch mới: chạy lại hàm này nhiều lần sẽ không
 * nhân đôi lịch, và đổi PHUT_MOI_LAN xong chỉ cần chạy lại là lịch đổi theo.
 */
function taoLichChay() {
  const HOP_LE = [1, 5, 10, 15, 30];
  if (HOP_LE.indexOf(PHUT_MOI_LAN) === -1) {
    throw new Error(
      'PHUT_MOI_LAN phải là một trong ' + HOP_LE.join(', ') + ' phút. '
      + 'Google chỉ cho đặt lịch theo các mốc này, đang để ' + PHUT_MOI_LAN + '.'
    );
  }

  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'syncWaitlist') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('syncWaitlist').timeBased().everyMinutes(PHUT_MOI_LAN).create();

  Logger.log('Đã đặt lịch: kéo dữ liệu mỗi ' + PHUT_MOI_LAN + ' phút.');
}
