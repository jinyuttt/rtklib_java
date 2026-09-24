program debug_pnm
  implicit none
  integer, parameter :: MAXDEG = 100
  real*8 :: pnm((MAXDEG+2)**2), dpt1((MAXDEG+2)**2), dpt2((MAXDEG+2)**2)
  real*8 :: t, lat_geo, rr, lon
  real*8 :: RAD, PI
  
  PI = 3.14159265358979323846d0
  RAD = PI / 180.0d0
  
  ! Same geodetic to geocentric as main test
  lat_geo = 29.484827d0
  t = sin(lat_geo * RAD)
  write(*,'(A,F14.10)') ' t = sin(lat_geo) = ', t
  
  call BelPnmdt(pnm, dpt1, dpt2, MAXDEG, t)
  
  ! Print key values
  ! Index mapping: for (n,m), code uses kk = n*(n+1)/2 + m + 1
  ! (1,0): kk=2, (1,1): kk=3, (2,0): kk=4, (2,1): kk=5, (3,0): kk=6
  write(*,'(A)') ' Index 1 (n=0,m=0) P00: ', ' pnm=', pnm(1)
  write(*,'(A)') ' Index 2 (n=1,m=0) P10: ', ' pnm=', pnm(2)
  write(*,'(A)') ' Index 3 (n=1,m=1) P11: ', ' pnm=', pnm(3)
  write(*,'(A)') ' Index 4 (n=2,m=0) P20: ', ' pnm=', pnm(4)
  write(*,'(A)') ' Index 5 (n=2,m=1) P21: ', ' pnm=', pnm(5)
  write(*,'(A)') ' Index 6 (n=2,m=2) P22: ', ' pnm=', pnm(6)
  write(*,'(A)') ' Index 7 (n=3,m=0) P30: ', ' pnm=', pnm(7)
  
  write(*,'(A)') ''
  write(*,'(A)') ' Derivatives dpt1:'
  write(*,'(A,F14.8)') ' dpt1(2) dP10:', dpt1(2)
  write(*,'(A,F14.8)') ' dpt1(3) dP11:', dpt1(3)
  write(*,'(A,F14.8)') ' dpt1(4) dP20:', dpt1(4)
  write(*,'(A,F14.8)') ' dpt1(5) dP21:', dpt1(5)
  
  ! Expected values
  write(*,'(A)') ''
  write(*,'(A)') ' Expected analytical values:'
  write(*,'(A,F14.8)') ' P10 = sqrt(3)*t:', sqrt(3.0d0)*t
  write(*,'(A,F14.8)') ' P11 = sqrt(3)*u:', sqrt(3.0d0)*sqrt(1-t*t)
  write(*,'(A,F14.8)') ' P20 = (3t^2-1)/sqrt(8)*sqrt(3):', &
       sqrt(3.0d0)*(3*t*t-1)/2.0d0
  
  ! Count M2 records used
  write(*,'(A)') ''
  write(*,'(A)') ' Checking index collision:'
  write(*,'(A,I4)') ' n=1,m=1 -> kk = ', 1*2/2+1+1
  write(*,'(A,I4)') ' n=2,m=0 -> kk = ', 2*3/2+0+1
end program debug_pnm

subroutine BelPnmdt(pnm,dpt1,dpt2,maxn,t)
  implicit none
  integer::maxn,n,m,kk,k1,k2,k3
  real*8::pnm((maxn+2)**2),dpt1((maxn+2)**2),dpt2((maxn+2)**2)
  real*8::t,u,a,b,c,d,e,anm,bnm,cnm_val
  pnm=0.d0;dpt1=0.d0;dpt2=0.d0
  u=dsqrt(1-t**2);pnm(1)=1.d0
  pnm(2)=dsqrt(3.d0)*t;pnm(3)=dsqrt(3.d0)*u
  do n=1,maxn
     a=dsqrt(2.d0*n+1.d0)/dsqrt(2.d0*n-1.d0)
     b=dsqrt(2.d0*(n-1.d0)*(2.d0*n+1.d0))/dsqrt((2.d0*n-1.d0)*n)
     kk=n*(n+1)/2+1;k1=n*(n-1)/2+1;k2=n*(n-1)/2+2
     pnm(kk)=a*t*pnm(k1)-b*u/2.d0*pnm(k2)
     do m=1,n
        kk=n*(n+1)/2+m+1;k1=n*(n-1)/2+m+1
        k2=n*(n-1)/2+m+2;k3=n*(n-1)/2+m
        c=a/n*dsqrt(dble(n**2-m**2))
        d=a/n/2.d0*dsqrt(dble((n-m)*(n-m-1)))
        e=a/n/2.d0*dsqrt(dble((n+m)*(n+m-1)))
        if(m==1)e=e*dsqrt(2.d0)
        pnm(kk)=c*t*pnm(k1)-d*u*pnm(k2)+e*u*pnm(k3)
     enddo
  enddo
  dpt1=0.d0;dpt2=0.d0
  dpt1(1)=0.d0;dpt1(2)=-dsqrt(3.d0)*u;dpt1(3)=dsqrt(3.d0)*t
  dpt2(1)=0.d0;dpt2(2)=-dsqrt(3.d0)*t;dpt2(3)=-dsqrt(3.d0)*u
  do n=2,maxn
     kk=n*(n+1)/2+1;k1=n*(n+1)/2+2
     dpt1(kk)=-dsqrt(dble(n*(n+1))/2.d0)*pnm(k1)
     kk=n*(n+1)/2+2;k1=n*(n+1)/2+1;k2=n*(n+1)/2+3
     anm=dsqrt(dble(2*n))*dsqrt(dble(n+1))/2.d0
     bnm=-dsqrt(dble(n-1))*dsqrt(dble(n+2))/2.d0
     dpt1(kk)=anm*pnm(k1)+bnm*pnm(k2)
     kk=n*(n+1)/2+1;k1=n*(n+1)/2+3
     anm=-dble(n*(n+1))/2.d0
     bnm=dsqrt(dble(n*(n-1)))*dsqrt(dble((n+1)*(n+2)))/dsqrt(8.d0)
     dpt2(kk)=anm*pnm(kk)+bnm*pnm(k1)
     kk=n*(n+1)/2+2;k1=n*(n+1)/2+4
     anm=-dble(2*n*(n+1)+(n-1)*(n+2))/4.d0
     bnm=dsqrt(dble((n-2)*(n-1)))*dsqrt(dble((n+2)*(n+3)))/4.d0
     dpt2(kk)=anm*pnm(kk)+bnm*pnm(k1)
     do m=2,n
        kk=n*(n+1)/2+m+1;k1=n*(n+1)/2+m;k2=n*(n+1)/2+m+2
        anm=dsqrt(dble(n+m))*dsqrt(dble(n-m+1))/2.d0
        bnm=-dsqrt(dble(n-m))*dsqrt(dble(n+m+1))/2.d0
        dpt1(kk)=anm*pnm(k1)+bnm*pnm(k2)
        k1=n*(n+1)/2+m-1;k2=n*(n+1)/2+m+3
        anm=dsqrt(dble((n-m+1)*(n-m+2)))*dsqrt(dble((n+m-1)*(n+m)))/4.d0
        bnm=-dble((n-m+1)*(n+m)+(n-m)*(n+m+1))/4.d0
        cnm_val=dsqrt(dble((n-m-1)*(n-m)))*dsqrt(dble((n+m+1)*(n+m+2)))/4.d0
        dpt2(kk)=anm*pnm(k1)+bnm*pnm(kk)+cnm_val*pnm(k2)
     enddo
  enddo
  return
end subroutine BelPnmdt
