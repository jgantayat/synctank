import { Component, OnInit, signal } from '@angular/core';
import { OrderControllerService, OrderResponse } from '../generated';

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
      error: () => this.error.set('Could not reach orders-backend on :8080.'),
    });
  }

  get formattedAmount(): string {
    return `$${this.order()?.amount?.toFixed(2) ?? '0.00'}`;
  }
}