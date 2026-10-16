/**
 * Đồng bộ danh sách chờ FONLIO từ backend về Google Sheet.
 *
 * Script GHI ĐÈ cả tab "Waitlist" mỗi lần có dữ liệu mới. Nhờ vậy thao tác là
 * bất biến: chạy lại bao nhiêu lần cũng ra cùng kết quả, không sinh dòng
 * trùng, và dòng nào sửa dưới database thì sheet cũng theo.
 *
 * ĐỪNG GÕ TAY vào tab Waitlist — lần đồng bộ sau sẽ xoá mất. Cần ghi chú hay
 * đánh dấu "đã liên hệ" thì tạo tab thứ hai rồi tham chiếu sang, ví dụ
 * =Waitlist!C2
 */

/* ──────────────────────────────────────────────────────────────────────────
   PHẦN CẤU HÌNH — chỉ có 2 dòng dưới đây là thứ bạn có thể cần đổi.
   ────────────────────────────────────────────────────────────────────────── */

const API_URL = 'https://backend-production-494f.up.railway.app/api/v1/waitlist/export';

/* Bao lâu kéo dữ liệu một lần (phút).
   Google CHỈ nhận 1, 5, 10, 15 hoặc 30 — số khác thì hàm taoLichChay báo lỗi
   rõ ràng thay vì để Google ném một lỗi khó hiểu.

   Đổi số này xong PHẢI chạy lại `taoLichChay`: lịch nằm trên máy chủ Google,
   sửa file ở đây không tự đổi được nó.

   Lưu ý về 1 phút: tài khoản Gmail thường chỉ được ~90 phút TỔNG thời gian
   chạy script mỗi ngày. 1 phút/lần là 1440 lượt, nên mỗi lượt phải thật rẻ —
   đó là lý do có đoạn "bỏ qua khi không có gì mới" bên dưới. Vượt hạn mức thì
   Google ngừng chạy lịch, và triệu chứng là sheet đứng im không báo gì. */
const PHUT_MOI_LAN = 1;

const SHEET_NAME = 'Waitlist';

/* Thứ tự cột trên sheet. Backend thêm cột mới thì thêm một dòng vào đây. */
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

/* ──────────────────────────────────────────────────────────────────────────
   HAI HÀM BẠN CHẠY TAY

   syncWaitlist  — kéo dữ liệu về NGAY BÂY GIỜ. Chạy khi muốn có dữ liệu liền,
                   không muốn đợi tới lượt kế tiếp.
   taoLichChay   — đặt hẹn giờ cho Google. KHÔNG kéo dữ liệu. Chạy một lần lúc
                   cài, và mỗi khi đổi PHUT_MOI_LAN.
   ────────────────────────────────────────────────────────────────────────── */

function syncWaitlist() {
  const props = PropertiesService.getScriptProperties();
  const token = props.getProperty('WAITLIST_EXPORT_TOKEN');
  if (!token) {
    throw new Error(
      'Chưa đặt WAITLIST_EXPORT_TOKEN trong Script properties. '
      + 'Vào bánh răng (Project Settings) → Script properties → Add script property.'
    );
  }

  const res = UrlFetchApp.fetch(API_URL, {
    method: 'get',
    headers: { 'X-Export-Token': token },
    muteHttpExceptions: true, // tự đọc mã lỗi để báo cho rõ, thay vì ném lỗi trống
  });

  const code = res.getResponseCode();
  if (code === 404) {
    throw new Error(
      'Backend trả 404. Hoặc token ở đây khác token của backend, '
      + 'hoặc backend chưa đặt WAITLIST_EXPORT_TOKEN, hoặc chưa deploy lại sau khi đặt.'
    );
  }
  if (code !== 200) {
    throw new Error('Backend trả ' + code + ': ' + res.getContentText().slice(0, 300));
  }

  const payload = JSON.parse(res.getContentText());
  if (!payload.success) {
    throw new Error(payload.message || 'Backend báo thất bại.');
  }

  const rows = payload.data || [];
  const sheet = laySheet();

  /* Bỏ qua khi không có gì mới.
     Ở nhịp 1 phút thì hầu hết các lượt là vô ích — danh sách chờ không có
     người đăng ký mỗi phút. Ghi lại cả sheet và autoResizeColumns là hai việc
     tốn thời gian nhất, nên chỉ làm khi dữ liệu THẬT SỰ đổi.

     Dấu nhận biết gồm số dòng VÀ nội dung dòng cuối: chỉ đếm số dòng thì một
     người sửa lại đăng ký cũ sẽ bị bỏ sót.

     Kèm điều kiện sheet đã có dữ liệu: nếu ai lỡ xoá sạch tab thì dấu vết cũ
     vẫn khớp, không kiểm thì script sẽ bỏ qua mãi và để tab trống. */
  const dauVet = rows.length + '|' + (rows.length ? JSON.stringify(rows[rows.length - 1]) : '');
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
  sheet.autoResizeColumns(1, header.length);

  props.setProperty('DAU_VET_LAN_TRUOC', dauVet);
  ghiMocDongBo(sheet, header.length, rows.length, false);
}

/**
 * Đặt lịch chạy định kỳ. KHÔNG kéo dữ liệu — chỉ đặt hẹn giờ.
 *
 * Gỡ lịch cũ trước rồi mới tạo lịch mới, nên chạy lại nhiều lần không bị nhân
 * đôi, và đổi PHUT_MOI_LAN xong chỉ cần chạy lại là lịch đổi theo.
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

/* ──────────────────────────────────────────────────────────────────────────
   HÀM PHỤ — không cần chạy tay
   ────────────────────────────────────────────────────────────────────────── */

function laySheet() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  return ss.getSheetByName(SHEET_NAME) || ss.insertSheet(SHEET_NAME);
}

/**
 * Mốc "Đồng bộ lúc…" cạnh bảng — cách duy nhất để biết lịch còn sống hay đã
 * chết im lặng. Google tắt trigger sau vài lần lỗi liên tiếp mà không ghi gì
 * lên sheet, nên không có mốc này thì không phân biệt được "chưa ai đăng ký"
 * với "script đã ngừng chạy từ hôm qua".
 */
function ghiMocDongBo(sheet, soCot, soNguoi, khongDoi) {
  sheet.getRange(1, soCot + 2).setValue(
    'Đồng bộ lúc ' + Utilities.formatDate(new Date(), 'Asia/Ho_Chi_Minh', 'dd/MM/yyyy HH:mm:ss')
      + ' · ' + soNguoi + ' người' + (khongDoi ? ' (không có thay đổi)' : '')
  );
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
