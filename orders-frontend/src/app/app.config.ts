import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { BASE_PATH } from './generated';
import { ORDERS_BASE } from './api-config';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withFetch()),
    // Day 11 — was the literal 'http://localhost:8080'. ORDERS_BASE resolves to exactly
    // that unless public/config.js says otherwise, so order-detail.spec.ts is unaffected.
    { provide: BASE_PATH, useValue: ORDERS_BASE },
  ]
};