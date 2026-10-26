-- ==============================================================================
-- V65__cleanup_other_external_jobs.sql
-- Giữ lại đúng 10 tin tuyển dụng Marketing được import từ file Excel,
-- xoá bỏ các tin rác / tin cũ khác khỏi bảng external_jobs ("Từ thị trường").
-- ==============================================================================

DELETE FROM external_jobs
WHERE source_key NOT IN (
    'fe72a74b03e6f3749db0d6d7abbfb1a8a1235bd4164bea4f97bae5ef24f46401',
    '4c93146e091ef37247fd06d837bd6cde2aa6daab96ffcdd888865697cadddf96',
    '2ce1aff3175a1ad48eb759f6124870cd1df005e8beb50ff7da13fe8e3efd3b27',
    '323bf4cbd983e5fd80b85929428a9fa5874da924b90ca88739a8d7ebb0f8bf74',
    '5a44a56939eb719c7efc9063479110dfbebddc620c9f9810c72df809ebc90f0e',
    '259e1bcb3103938d07cd24ab156f92968938d013a2d5cf0009de83c6ef66a1b4',
    'fca1ce4adbbc64c08af33c88e9811db83c0ccfadc0c285a8c6e245b317a2e880',
    'ab8bee80267d61b537b30a1f7f88756e4a144dac600816c70087d75bfa8e35ef',
    '90ae975f6167a7e91d9b68c87d1ab761d18b49c6853930ab174686a40c562eb8',
    '4ee70c05adbe1c2656f8a350ba3fa9fad4bfefcf2d38c22beca0b14aab2e3e16'
);
