// supabase/functions/sepay-webhook/index.ts
// 
// Supabase Edge Function nhận webhook từ SePay
// Khi khách chuyển khoản thành công, SePay gọi đến URL này
// Function sẽ:
// 1. Xác thực request (API key)
// 2. Parse thông tin giao dịch
// 3. Match với payment record qua nội dung chuyển khoản (transfer_content)
// 4. Cập nhật payment status: pending → escrow
// 5. Cập nhật booking status: confirmed → in_progress
//
// Cấu hình biến môi trường cần thiết:
// - SEPAY_WEBHOOK_SECRET: secret key để xác thực webhook từ SePay (optional)
// - SUPABASE_URL: tự có sẵn
// - SUPABASE_SERVICE_ROLE_KEY: tự có sẵn (dùng để bypass RLS)

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// Interface mô tả payload webhook từ SePay
interface SePayWebhookPayload {
  id: number;                    // ID giao dịch SePay
  gateway: string;               // Mã ngân hàng (VD: "MBBank")
  transactionDate: string;       // Ngày giao dịch
  accountNumber: string;         // Số tài khoản nhận
  subAccount: string | null;     // Tài khoản phụ
  transferType: string;          // "in" = tiền vào, "out" = tiền ra
  transferAmount: number;        // Số tiền
  accumulated: number;           // Số dư tích lũy
  code: string | null;           // Mã tham chiếu
  content: string;               // Nội dung chuyển khoản ← QUAN TRỌNG
  referenceCode: string;         // Mã tham chiếu ngân hàng
  description: string;           // Mô tả
}

Deno.serve(async (req: Request) => {
  // Chỉ chấp nhận POST
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ success: false, message: "Method not allowed" }), {
      status: 405,
      headers: { "Content-Type": "application/json" },
    });
  }

  try {
    // ─── 1. Xác thực webhook (optional - nếu SePay có gửi secret) ────────
    const webhookSecret = Deno.env.get("SEPAY_WEBHOOK_SECRET");
    if (webhookSecret) {
      const authHeader = req.headers.get("Authorization");
      const token = authHeader?.replace("Bearer ", "").replace("Apikey ", "");
      if (token !== webhookSecret) {
        console.error("Webhook auth failed");
        return new Response(JSON.stringify({ success: false, message: "Unauthorized" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        });
      }
    }

    // ─── 2. Parse body ───────────────────────────────────────────────────
    const payload: SePayWebhookPayload = await req.json();
    console.log("SePay webhook received:", JSON.stringify(payload));

    // Chỉ xử lý giao dịch tiền VÀO
    if (payload.transferType !== "in") {
      console.log("Skipping: not an incoming transfer");
      return new Response(JSON.stringify({ success: true, message: "Skipped: outgoing transfer" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    // ─── 3. Trích xuất mã giao dịch từ nội dung CK ──────────────────────
    // Nội dung CK format: "FIXBID XXXXXXXX" (8 ký tự)
    const content = (payload.content || "").toUpperCase().trim();
    const match = content.match(/FIXBID\s+([A-Z0-9]{8})/);

    if (!match) {
      console.log("Skipping: content does not match FIXBID pattern:", content);
      return new Response(JSON.stringify({ success: true, message: "Skipped: no matching pattern" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    const transferContent = `FIXBID ${match[1]}`;
    console.log("Matched transfer_content:", transferContent);

    // ─── 4. Kết nối Supabase (dùng service role để bypass RLS) ───────────
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    // ─── 5. Tìm payment record theo transfer_content ─────────────────────
    const { data: payment, error: findError } = await supabase
      .from("payments")
      .select("id, booking_id, amount, status")
      .eq("transfer_content", transferContent)
      .eq("status", "pending")
      .single();

    if (findError || !payment) {
      console.log("Payment not found for:", transferContent, findError?.message);
      return new Response(JSON.stringify({ 
        success: false, 
        message: "Payment not found or already processed" 
      }), {
        status: 200, // Vẫn trả 200 để SePay không retry
        headers: { "Content-Type": "application/json" },
      });
    }

    // ─── 6. Kiểm tra số tiền khớp ───────────────────────────────────────
    if (payload.transferAmount < payment.amount) {
      console.error(
        `Amount mismatch: received ${payload.transferAmount}, expected ${payment.amount}`
      );
      // Vẫn cập nhật nhưng đánh dấu là processing (chưa đủ tiền)
      await supabase
        .from("payments")
        .update({ 
          status: "processing",
          transaction_id: String(payload.id),
        })
        .eq("id", payment.id);

      return new Response(JSON.stringify({ 
        success: true, 
        message: "Amount insufficient, marked as processing" 
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    // ─── 7. Cập nhật payment status → escrow ─────────────────────────────
    const { error: updatePaymentError } = await supabase
      .from("payments")
      .update({
        status: "escrow",
        transaction_id: String(payload.id),
        paid_at: new Date().toISOString(),
      })
      .eq("id", payment.id);

    if (updatePaymentError) {
      console.error("Failed to update payment:", updatePaymentError.message);
      return new Response(JSON.stringify({ success: false, message: "DB update failed" }), {
        status: 500,
        headers: { "Content-Type": "application/json" },
      });
    }

    // ─── 8. Cập nhật booking status → in_progress ────────────────────────
    // Thợ giờ có thể bắt đầu thực hiện công việc
    const { error: updateBookingError } = await supabase
      .from("bookings")
      .update({
        status: "in_progress",
        updated_at: new Date().toISOString(),
      })
      .eq("id", payment.booking_id)
      .eq("status", "confirmed"); // Chỉ update nếu đang ở confirmed

    if (updateBookingError) {
      console.error("Failed to update booking:", updateBookingError.message);
      // Payment đã được cập nhật, booking sẽ được xử lý sau
    }

    console.log(`✅ Payment ${payment.id} → escrow, Booking ${payment.booking_id} → in_progress`);

    // ─── 9. Trả về success cho SePay ─────────────────────────────────────
    return new Response(JSON.stringify({ 
      success: true, 
      message: "Payment confirmed",
      paymentId: payment.id,
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });

  } catch (error) {
    console.error("Webhook processing error:", error);
    return new Response(JSON.stringify({ success: false, message: "Internal error" }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
});
