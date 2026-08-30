import { Component, signal } from '@angular/core';
import { OrderDetail } from './order-detail/order-detail';
import { ContractAgent } from './contract-agent/contract-agent';

@Component({
  selector: 'app-root',
  imports: [OrderDetail, ContractAgent],
  // Inline template, as on main. app.html is dead scaffold from the CLI.
  template: `
    <app-order-detail></app-order-detail>
    <app-contract-agent></app-contract-agent>
  `,
  styleUrl: './app.css'
})
export class App {
  protected readonly title = signal('orders-frontend');
}