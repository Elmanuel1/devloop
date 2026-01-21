package com.tosspaper.service.impl;

import com.tosspaper.models.domain.PaymentTerm;
import com.tosspaper.models.service.PaymentTermService;
import com.tosspaper.payment_terms.PaymentTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTermServiceImpl implements PaymentTermService {
    
    private final PaymentTermRepository paymentTermRepository;
    
    @Override
    public void upsertFromProvider(Long companyId, String provider, List<PaymentTerm> terms) {
        paymentTermRepository.upsertFromProvider(companyId, provider, terms);
    }
}



