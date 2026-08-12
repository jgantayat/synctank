
import { Component, OnInit, signal } from '@angular/core';
import { OrderControllerService, OrderResponse } from '../generated';

@Component({
  selector: 'app-order-detail',
  imports: [],
  templateUrl: './order-detail.html',
  styleUrl: './order-detail.css',
})
export class OrderDetail implements OnInit {
  // A signal, not a plain field — Angular 21 apps are zoneless by default, so a
  // plain `this.order = order` inside .subscribe() isn't guaranteed to trigger a
  // re-render. Writing to a signal notifies the template correctly either way.
  order = signal<OrderResponse | undefined>(undefined);

  constructor(private orderApi: OrderControllerService) {}

  ngOnInit(): void {
     this.orderApi.getOrder({ id: 1 }).subscribe((order) => {
      this.order.set(order);
    });
  }

  // Deliberately touches `.amount` directly in the .ts file — this is the exact
  // line that will fail to compile in Phase 5, matching your doc's own demo script.
  get formattedAmount(): string {
    return `$${this.order()?.amount?.toFixed(2) ?? '0.00'}`;
  }
} 