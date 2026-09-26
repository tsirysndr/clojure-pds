// Deploy with Cloudflare Email Service and a verified sending domain.
import { DurableObject } from 'cloudflare:workers';
import { handleRequest, deliver } from './handler.mjs';

export default { fetch: handleRequest };

// One durable object per idempotency key serializes duplicate requests.
export class Delivery extends DurableObject {
  async fetch(request) {
    return this.ctx.blockConcurrencyWhile(() => deliver(request, this.env, this.ctx.storage));
  }
}
