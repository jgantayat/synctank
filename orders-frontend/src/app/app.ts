import { Component, signal } from '@angular/core';
import { OrderDetail } from './order-detail/order-detail';
import { Dashboard } from './dashboard/dashboard';
import { ContractAgent } from './contract-agent/contract-agent';

@Component({
  selector: 'app-root',
  imports: [OrderDetail, Dashboard, ContractAgent],
  // Inline template. Day 08 deleted app.html -- it was untouched CLI scaffold that this
  // template has never referenced, and it read like the real dashboard markup to anyone
  // opening the obvious file.
  //
  // Order is the pitch order: the screen that breaks (Level 1), the evidence
  // (Level 2), then the agent that changes the contract (Level 3).
  template: `
    <app-order-detail></app-order-detail>
    <app-dashboard></app-dashboard>
    <app-contract-agent></app-contract-agent>
  `,
  styleUrl: './app.css'
})
export class App {
  protected readonly title = signal('orders-frontend');
}