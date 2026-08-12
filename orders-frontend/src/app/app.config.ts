import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideClientHydration, withEventReplay } from '@angular/platform-browser';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { BASE_PATH } from './generated';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
     provideClientHydration(withEventReplay()),
     provideHttpClient(withFetch()),
    { provide: BASE_PATH, useValue: 'http://localhost:8080' },
    
  ]
};
