! debug_m2.f90 - Print first 10 M2 terms to compare with Java
program debug_m2
  implicit none
  integer, parameter :: MAXDEG = 100
  real*8, parameter :: PI = 3.14159265358979323846d0
  real*8, parameter :: RAD = PI / 180.0d0
  real*8, parameter :: G_CONST = 6.67428d-11
  real*8, parameter :: RHO_W = 1.025d3
  real*8, parameter :: GE = 9.7803278d0
  real*8, parameter :: AE = 6378137.0d0
  real*8, parameter :: GM_VAL = 3.986004415d14

  real*8 :: lat_deg, lon_deg, hgt
  integer, parameter :: MAX_REC = 50000
  real*8 :: fes_doodson(MAX_REC), fes_cplus(MAX_REC), fes_epsplus(MAX_REC)
  real*8 :: fes_cminus(MAX_REC), fes_epsminus(MAX_REC)
  integer :: fes_n(MAX_REC), fes_m(MAX_REC)
  integer :: nn_rec
  real*8 :: love_h(0:MAXDEG), love_l(0:MAXDEG)
  real*8 :: pnm((MAXDEG+2)**2), dpt1((MAXDEG+2)**2), dpt2((MAXDEG+2)**2)
  real*8 :: rr, lat_geo, lon
  real*8 :: sin_theta, cos_theta, t_val
  real*8 :: ae_over_rr, gr, fk, scale
  integer :: i, n, m, j, idx, count
  real*8 :: ratio_n, cos_ml, sin_ml
  real*8 :: eps_p, eps_m, factor
  real*8 :: cp, sp, cn, sn
  real*8 :: z_cnm_re, z_cnm_im, z_snm_re, z_snm_im
  real*8 :: z_re, z_im
  real*8 :: p_val, dp_val, dh, dl
  real*8 :: up_re, up_im, sum_re, sum_im

  lat_deg = 29.6500d0
  lon_deg = 94.3333d0
  hgt = 0.0d0

  call read_fes2004(fes_doodson, fes_n, fes_m, fes_cplus, fes_epsplus, &
                    fes_cminus, fes_epsminus, MAX_REC, nn_rec)
  call read_love(love_h, love_l, MAXDEG)
  call geodetic_to_geocentric(lat_deg, lon_deg, hgt, rr, lat_geo, lon)

  sin_theta = sin(lat_geo * RAD)
  cos_theta = cos(lat_geo * RAD)
  t_val = sin_theta
  ae_over_rr = AE / rr
  gr = normal_gravity(lat_deg)
  fk = 4.0d0 * PI * G_CONST * RHO_W / GE
  scale = fk * 1.0d-2 * GM_VAL / (rr * gr)

  write(*,'(A,F12.6)') 'rr = ', rr
  write(*,'(A,F12.6)') 'lat_geo = ', lat_geo
  write(*,'(A,F12.8)') 'gr = ', gr
  write(*,'(A,E16.8)') 'scale = ', scale
  write(*,*)

  call BelPnmdt(pnm, dpt1, dpt2, MAXDEG, t_val)

  ! Find M2 (doodson = 255.555)
  sum_re = 0.0d0
  sum_im = 0.0d0
  count = 0

  do idx = 1, nn_rec
     if (abs(fes_doodson(idx) - 255.555d0) .lt. 0.0005d0) then
        n = fes_n(idx)
        m = fes_m(idx)
        if (n < 1 .or. n > MAXDEG) cycle

        count = count + 1
        if (count > 10) exit  ! Only print first 10

        ratio_n = (ae_over_rr) ** n
        j = n * (n + 1) / 2 + m

        cos_ml = cos(m * lon * RAD)
        sin_ml = sin(m * lon * RAD)

        eps_p = fes_epsplus(idx) * RAD
        eps_m = fes_epsminus(idx) * RAD
        factor = 1.0d0 / (2 * n + 1)
        cp = fes_cplus(idx) * sin(eps_p) * factor
        sp = fes_cplus(idx) * cos(eps_p) * factor
        cn = fes_cminus(idx) * sin(eps_m) * factor
        sn = fes_cminus(idx) * cos(eps_m) * factor

        z_cnm_re = cp + cn
        z_cnm_im = -(sp + sn)
        z_snm_re = sp - sn
        z_snm_im = cp - cn

        z_re = z_cnm_re * cos_ml + z_snm_re * sin_ml
        z_im = z_cnm_im * cos_ml + z_snm_im * sin_ml

        dh = love_h(n)
        dl = love_l(n)
        p_val = pnm(j + 1)
        dp_val = dpt1(j + 1)

        up_re = z_re * p_val * dh
        up_im = z_im * p_val * dh

        sum_re = sum_re + ratio_n * up_re
        sum_im = sum_im + ratio_n * up_im

        write(*,'(A,I3,A,I3,A,I3,A,E12.5,A,E12.5)') &
             'n=', n, ' m=', m, ' j=', j, &
             ' pnm=', p_val, ' dh=', dh
        write(*,'(A,E12.5,A,E12.5)') '  z_re=', z_re, ' z_im=', z_im
        write(*,'(A,E12.5,A,E12.5)') '  term_re=', ratio_n*up_re, ' term_im=', ratio_n*up_im
        write(*,'(A,E12.5,A,E12.5)') '  sum_re=', sum_re, ' sum_im=', sum_im
        write(*,*)
     endif
  enddo

  write(*,'(A,I6)') 'Total M2 records: ', count
  write(*,'(A,E16.8)') 'Final sum_re: ', sum_re
  write(*,'(A,E16.8)') 'Final sum_im: ', sum_im
  write(*,'(A,E16.8)') 'After scale (mm): ', sqrt((scale*sum_re)**2 + (scale*sum_im)**2) * 1000.0d0

contains

  subroutine read_fes2004(dood, fn, fm, fcp, fep, fcm, fem, maxr, nrec)
    real*8 :: dood(maxr), fcp(maxr), fep(maxr), fcm(maxr), fem(maxr)
    integer :: fn(maxr), fm(maxr), maxr, nrec
    character*256 :: line
    character*10 :: darwin_name
    real*8 :: st(12)
    integer :: i, ios, lu
    logical :: is_blq

    lu = 20
    open(lu, file='FES2004S1.dat', status='old', iostat=ios)
    if (ios .ne. 0) then
       write(*,*) 'ERROR: cannot open FES2004S1.dat'
       stop 1
    endif
    do i = 1, 3
       read(lu, '(a)') line
    enddo

    nrec = 0
    do
       read(lu, *, iostat=ios) st(1), darwin_name, st(2), st(3), &
            st(4), st(5), st(6), st(7), st(8), st(9), st(10), st(11)
       if (ios .ne. 0) exit

       is_blq = .false.
       if (nint(st(1)*1000) == 255555) is_blq = .true.

       if (is_blq .and. nrec < maxr) then
          nrec = nrec + 1
          dood(nrec) = st(1)
          fn(nrec) = nint(st(2))
          fm(nrec) = nint(st(3))
          fcp(nrec) = st(8)
          fep(nrec) = st(9)
          fcm(nrec) = st(10)
          fem(nrec) = st(11)
       endif
    enddo
    close(lu)
  end subroutine read_fes2004

  subroutine read_love(lh, ll, maxdeg)
    real*8 :: lh(0:maxdeg), ll(0:maxdeg)
    integer :: maxdeg
    integer :: i, ios, lu, deg
    character*256 :: line
    real*8 :: h, l, k

    lu = 21
    open(lu, file='Love_load_cm.dat', status='old', iostat=ios)
    if (ios .ne. 0) then
       write(*,*) 'ERROR: cannot open Love_load_cm.dat'
       stop 1
    endif
    do i = 1, 6
       read(lu, '(a)') line
    enddo

    lh = 0.0d0
    ll = 0.0d0
    do
       read(lu, *, iostat=ios) deg, h, l, k
       if (ios .ne. 0) exit
       if (deg > maxdeg) exit
       lh(deg) = h
       ll(deg) = l
    enddo
    close(lu)
  end subroutine read_love

  subroutine geodetic_to_geocentric(lat_d, lon_d, h, r, lat_g, lon_out)
    real*8 :: lat_d, lon_d, h, r, lat_g, lon_out
    real*8 :: a, f, e2, lat_r, lon_r, sin_lat, cos_lat
    real*8 :: nn, x, y, z

    a = 6378137.0d0
    f = 1.0d0 / 298.25641153d0
    e2 = (2.0d0 - f) * f
    lat_r = lat_d * RAD
    lon_r = lon_d * RAD
    sin_lat = sin(lat_r)
    cos_lat = cos(lat_r)
    nn = a / sqrt(1.0d0 - e2 * sin_lat**2)
    x = (nn + h) * cos_lat * cos(lon_r)
    y = (nn + h) * cos_lat * sin(lon_r)
    z = (nn * (1.0d0 - e2) + h) * sin_lat
    r = sqrt(x**2 + y**2 + z**2)
    lat_g = atan2(z, sqrt(x**2 + y**2)) / RAD
    lon_out = lon_d
  end subroutine geodetic_to_geocentric

  function normal_gravity(lat_d) result(gr)
    real*8 :: lat_d, gr
    real*8 :: sin_lat2, k, e2, g_eq

    sin_lat2 = sin(lat_d * RAD)
    sin_lat2 = sin_lat2 * sin_lat2
    k = 1.931851353d-3
    e2 = 6.69437999014d-3
    g_eq = 9.7803278d0
    gr = g_eq * (1.0d0 + k * sin_lat2) / sqrt(1.0d0 - e2 * sin_lat2)
  end function normal_gravity

end program debug_m2

subroutine BelPnmdt(pnm,dpt1,dpt2,maxn,t)
  implicit none
  integer::maxn,n,m,k,kk,k1,k2,k3
  real*8::pnm((maxn+2)**2),dpt1((maxn+2)**2),dpt2((maxn+2)**2)
  real*8::t,u,a,b,c,d,e
  real*8::anm,bnm,cnm_val

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
        c=a/n*dsqrt(dble(n**2-m**2));d=a/n/2.d0*dsqrt(dble((n-m)*(n-m-1)))
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
