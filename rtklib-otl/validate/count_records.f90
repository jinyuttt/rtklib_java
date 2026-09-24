program count_records
  implicit none
  integer :: lu, ios, i, n, m, nrec_m2
  real*8 :: st(12)
  character*256 :: line
  character*10 :: darwin_name
  real*8 :: doodson
  
  lu = 20
  open(lu, file='FES2004S1.dat', status='old')
  do i = 1, 3; read(lu,'(a)') line; enddo
  
  nrec_m2 = 0
  do
     read(lu, *, iostat=ios) st(1), darwin_name, st(2), st(3), &
          st(4), st(5), st(6), st(7), st(8), st(9), st(10), st(11)
     if (ios .ne. 0) exit
     doodson = st(1)
     n = nint(st(2))
     m = nint(st(3))
     if (nint(doodson*1000) == 255555) then
        nrec_m2 = nrec_m2 + 1
        if (nrec_m2 <= 5) then
           write(*,'(A,I5,A,I4,A,I4,A,F10.5,A,F10.4,A,F10.4)') &
                '#', nrec_m2, ' n=', n, ' m=', m, &
                ' C+=', st(8), ' eps+=', st(9), &
                ' C-=', st(10)
        endif
     endif
  enddo
  write(*,'(A,I8)') ' Total M2 records: ', nrec_m2
  close(lu)
end program count_records
