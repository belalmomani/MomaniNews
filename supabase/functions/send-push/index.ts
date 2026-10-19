// Supabase Edge Function: يستقبل Database Webhook عند إدراج صف في notifications ويبثّه عبر FCM لموضوع "news".
// الأسرار (PUSH_WEBHOOK_SECRET, FCM_SERVICE_ACCOUNT) تُحفظ بـ `supabase secrets set` فقط، لا في المستودع.
import { handle } from "./core.mjs";

// @ts-ignore Deno global
Deno.serve((req: Request) =>
  handle(req, {
    // @ts-ignore Deno global
    PUSH_WEBHOOK_SECRET: Deno.env.get("PUSH_WEBHOOK_SECRET"),
    // @ts-ignore Deno global
    FCM_SERVICE_ACCOUNT: Deno.env.get("FCM_SERVICE_ACCOUNT"),
  })
);
