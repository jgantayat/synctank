import { Component, OnInit, signal } from '@angular/core';
import { OrderControllerService, OrderResponse } from '../generated';
import { ORDERS_BASE } from '../api-config';

@Component({
  selector: 'app-order-detail',
  imports: [],
  templateUrl: './order-detail.html',
  styleUrl: './order-detail.css',
})
export class OrderDetail implements OnInit {
  order = signal<OrderResponse | undefined>(undefined);

  // Day 08: added alongside the error handler below. Without this the panel renders
  // an empty DOM node on any failure -- see the CORS analysis; a blank panel is
  // indistinguishable from "still loading" during a live demo.
  error = signal<string | null>(null);

  constructor(private orderApi: OrderControllerService) {}

  ngOnInit(): void {
    this.orderApi.getOrder({ id: 1 }).subscribe({
      next: (order) => this.order.set(order),
      error: () => this.error.set(`Could not reach orders-backend at ${ORDERS_BASE}.`),
    });
  }

  // was: `$${this.order()?.amount?.toFixed(2) ?? '0.00'}` — `amount: number` became
  // `total: Money { value, currency }`, so the currency now comes from the contract too.
  get formattedAmount(): string {
    const total = this.order()?.total;
    if (total?.value === undefined || total.value === null) {
      return '0.00';
    }
    return `${total.currency ?? ''} ${Number(total.value).toFixed(2)}`.trim();
  }
}