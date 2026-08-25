import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { OrderDetail } from './order-detail';

describe('OrderDetail', () => {
  let component: OrderDetail;
  let fixture: ComponentFixture<OrderDetail>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OrderDetail],
      // provideHttpClientTesting intercepts requests so ngOnInit's getOrder() call
      // is captured by the mock instead of hitting a real (absent) backend.
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(OrderDetail);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges(); // triggers ngOnInit -> fires the getOrder request
  });

  it('should create', () => {
    // Answer the request ngOnInit made, so nothing escapes as an unhandled error.
    const req = httpMock.expectOne('http://localhost:8080/api/orders/1');
    req.flush({ id: 1, customerName: 'Test', amount: 10, status: 'SHIPPED' });
    expect(component).toBeTruthy();
    httpMock.verify();
  });
});