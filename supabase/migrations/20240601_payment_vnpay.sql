-- ============================================================
-- Migration: VNPay Payment Integration
-- Description: Add VNPay payment support with escrow system
-- ============================================================

-- 1. Add new columns to payments table
ALTER TABLE payments
  ADD COLUMN IF NOT EXISTS vnpay_url TEXT,
  ADD COLUMN IF NOT EXISTS escrow_status TEXT DEFAULT 'none'
    CHECK (escrow_status IN ('none', 'holding', 'released', 'refunded')),
  ADD COLUMN IF NOT EXISTS escrow_released_at TIMESTAMPTZ;

-- 2. Update payments status check constraint to include new statuses
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
  CHECK (status IN ('pending', 'processing', 'escrow_held', 'completed', 'failed', 'refunded'));

-- 3. Add 'awaiting_payment' to bookings status
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
  CHECK (status IN (
    'pending', 'bidding', 'confirmed', 'awaiting_payment',
    'in_progress', 'pending_completion', 'completed',
    'cancelled', 'disputed'
  ));

-- 4. Create worker_wallets table
CREATE TABLE IF NOT EXISTS worker_wallets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  worker_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  balance NUMERIC(12, 2) DEFAULT 0.00,
  total_earned NUMERIC(12, 2) DEFAULT 0.00,
  total_withdrawn NUMERIC(12, 2) DEFAULT 0.00,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE(worker_id)
);

-- 5. Create wallet_transactions table
CREATE TABLE IF NOT EXISTS wallet_transactions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  wallet_id UUID NOT NULL REFERENCES worker_wallets(id) ON DELETE CASCADE,
  booking_id UUID REFERENCES bookings(id),
  type TEXT NOT NULL CHECK (type IN ('earning', 'withdrawal', 'refund', 'platform_fee')),
  amount NUMERIC(12, 2) NOT NULL,
  balance_after NUMERIC(12, 2) NOT NULL,
  description TEXT DEFAULT '',
  created_at TIMESTAMPTZ DEFAULT now()
);

-- 6. Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_payments_booking_id ON payments(booking_id);
CREATE INDEX IF NOT EXISTS idx_payments_customer_id ON payments(customer_id);
CREATE INDEX IF NOT EXISTS idx_payments_worker_id ON payments(worker_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status);
CREATE INDEX IF NOT EXISTS idx_worker_wallets_worker_id ON worker_wallets(worker_id);
CREATE INDEX IF NOT EXISTS idx_wallet_transactions_wallet_id ON wallet_transactions(wallet_id);
CREATE INDEX IF NOT EXISTS idx_wallet_transactions_booking_id ON wallet_transactions(booking_id);

-- 7. RLS Policies for worker_wallets
ALTER TABLE worker_wallets ENABLE ROW LEVEL SECURITY;

-- Worker can view their own wallet
CREATE POLICY "Workers can view own wallet" ON worker_wallets
  FOR SELECT USING (auth.uid() = worker_id);

-- System/service can insert/update wallets (via service_role key)
CREATE POLICY "Service can manage wallets" ON worker_wallets
  FOR ALL USING (true) WITH CHECK (true);

-- 8. RLS Policies for wallet_transactions
ALTER TABLE wallet_transactions ENABLE ROW LEVEL SECURITY;

-- Worker can view transactions of their own wallet
CREATE POLICY "Workers can view own transactions" ON wallet_transactions
  FOR SELECT USING (
    wallet_id IN (
      SELECT id FROM worker_wallets WHERE worker_id = auth.uid()
    )
  );

-- Service can insert transactions
CREATE POLICY "Service can manage transactions" ON wallet_transactions
  FOR ALL USING (true) WITH CHECK (true);

-- 9. Trigger function: auto-calculate platform_fee and worker_receives on payment insert
CREATE OR REPLACE FUNCTION calculate_payment_fees()
RETURNS TRIGGER AS $$
BEGIN
  -- 10% platform fee
  IF NEW.platform_fee IS NULL OR NEW.platform_fee = 0 THEN
    NEW.platform_fee := NEW.amount * 0.10;
  END IF;
  IF NEW.worker_receives IS NULL OR NEW.worker_receives = 0 THEN
    NEW.worker_receives := NEW.amount - NEW.platform_fee;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_calculate_payment_fees ON payments;
CREATE TRIGGER trigger_calculate_payment_fees
  BEFORE INSERT ON payments
  FOR EACH ROW
  EXECUTE FUNCTION calculate_payment_fees();

-- 10. Function to release escrow (can be called from app or edge function)
CREATE OR REPLACE FUNCTION release_escrow(p_booking_id UUID)
RETURNS void AS $$
DECLARE
  v_payment RECORD;
  v_wallet_id UUID;
  v_balance NUMERIC;
BEGIN
  -- Get payment
  SELECT * INTO v_payment FROM payments
    WHERE booking_id = p_booking_id AND escrow_status = 'holding'
    LIMIT 1;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'No escrow payment found for booking %', p_booking_id;
  END IF;

  -- Update payment
  UPDATE payments SET
    status = 'completed',
    escrow_status = 'released',
    escrow_released_at = now()
  WHERE id = v_payment.id;

  -- Upsert wallet
  INSERT INTO worker_wallets (worker_id, balance, total_earned)
  VALUES (v_payment.worker_id, v_payment.worker_receives, v_payment.worker_receives)
  ON CONFLICT (worker_id) DO UPDATE SET
    balance = worker_wallets.balance + v_payment.worker_receives,
    total_earned = worker_wallets.total_earned + v_payment.worker_receives,
    updated_at = now();

  -- Get wallet id and balance
  SELECT id, balance INTO v_wallet_id, v_balance
    FROM worker_wallets WHERE worker_id = v_payment.worker_id;

  -- Insert transaction
  INSERT INTO wallet_transactions (wallet_id, booking_id, type, amount, balance_after, description)
  VALUES (
    v_wallet_id,
    p_booking_id,
    'earning',
    v_payment.worker_receives,
    v_balance,
    'Thu nhập từ công việc #' || LEFT(p_booking_id::text, 8)
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
