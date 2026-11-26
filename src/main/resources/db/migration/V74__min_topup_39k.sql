-- V74: Nâng mức nạp tối thiểu từ 10.000đ lên 39.000đ (bằng gói nhỏ nhất).
-- Backend đọc giá trị này qua ConfigService (khoá min_topup_vnd), nên phải sửa
-- ở DB — đổi hằng số trong code thôi thì môi trường đã seed vẫn chặn theo 10k.
update system_configs
   set value_int = 39000,
       updated_at = now()
 where config_key = 'min_topup_vnd';

