-- Migration: Thêm hỗ trợ SePay payment
-- Chạy SQL này trong Supabase Dashboard → SQL Editor

-- 1. Thêm giá trị 'sepay' vào enum payment_method
ALTER TYPE payment_method ADD VALUE IF NOT EXISTS 'sepay';

-- 2. Thêm giá trị 'escrow' vào enum payment_status
ALTER TYPE payment_status ADD VALUE IF NOT EXISTS 'escrow';

-- 3. Thêm column transfer_content để lưu nội dung chuyển khoản (dùng match với SePay webhook)
ALTER TABLE payments ADD COLUMN IF NOT EXISTS transfer_content TEXT;

-- 4. Tạo index để tìm payment nhanh theo transfer_content
CREATE INDEX IF NOT EXISTS idx_payments_transfer_content 
ON payments(transfer_content) 
WHERE transfer_content IS NOT NULL;

-- 5. Tạo index cho status + transfer_content (dùng khi webhook tìm pending payments)
CREATE INDEX IF NOT EXISTS idx_payments_status_transfer_content 
ON payments(status, transfer_content) 
WHERE transfer_content IS NOT NULL;
