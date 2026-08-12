import { Component, signal } from '@angular/core';
import { OrderDetail } from './order-detail/order-detail';
@Component({
  selector: 'app-root',
  imports: [OrderDetail],
  // templateUrl: './app.html',
  template: `<app-order-detail></app-order-detail>`,
  styleUrl: './app.css'
})
export class App {
  protected readonly title = signal('orders-frontend');
}
