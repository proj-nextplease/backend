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
 *  5. Chạy hàm `taoLichChay` một lần để đặt lịch tự chạy mỗi giờ.
 *
 * Script GHI ĐÈ cả sheet mỗi lần chạy. Nhờ vậy thao tác là bất biến: chạy lại
 * bao nhiêu lần cũng ra cùng kết quả, không sinh dòng trùng, và dòng nào sửa
 * dưới DB thì sheet cũng theo. Đừng gõ tay vào sheet này — lần đồng bộ sau sẽ
 * xoá mất; cần ghi chú thì thêm một sheet khác rồi tham chiếu sang.
 */

const API_URL = 'https://api.fonlio.vn/api/v1/waitlist/export'; // ← đổi thành domain backend thật
const SHEET_NAME = 'Waitlist';

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
  const header = COLUMNS.map(function (c) { return c[1]; });
  const values = [header].concat(rows.map(function (row) {
    return COLUMNS.map(function (c) { return dinhDang(row[c[0]]); });
  }));

  const sheet = laySheet();
  sheet.clearContents();
  sheet.getRange(1, 1, values.length, header.length).setValues(values);
  sheet.setFrozenRows(1);
  sheet.getRange(1, 1, 1, header.length).setFontWeight('bold');
  sheet.autoResizeColumns(1, header.length);

  // Dấu vết lần chạy gần nhất, để biết lịch còn sống hay đã chết im lặng.
  sheet.getRange(1, header.length + 2).setValue(
    'Đồng bộ lúc ' + Utilities.formatDate(new Date(), 'Asia/Ho_Chi_Minh', 'dd/MM/yyyy HH:mm')
      + ' · ' + rows.length + ' người'
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

/** Đặt lịch chạy mỗi giờ. Gỡ lịch cũ trước để chạy lại hàm này không nhân đôi. */
function taoLichChay() {
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'syncWaitlist') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('syncWaitlist').timeBased().everyHours(1).create();
}
